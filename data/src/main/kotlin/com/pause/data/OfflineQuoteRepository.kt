package com.pause.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.pause.domain.BreathingQuote
import com.pause.domain.QuoteRepository
import com.pause.domain.ZEN_QUOTES_SOURCE_URL
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random

/** A single application-scoped repository. It never contacts ZenQuotes or another server. */
class OfflineQuoteRepository internal constructor(
    private val loadLibrary: suspend () -> QuoteLibrary?,
    private val persistence: QuoteRotationPersistence,
    private val random: Random = Random.Default,
) : QuoteRepository {
    constructor(context: Context, scope: CoroutineScope) : this(
        context, scope, "pause_quote_rotation",
    )

    internal constructor(context: Context, scope: CoroutineScope, storeName: String) : this(
        loadLibrary = {
            val json = context.applicationContext.assets.open(QUOTE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            QuoteLibraryCodec.decode(json)
        },
        persistence = DataStoreQuoteRotation(context.applicationContext, scope, storeName),
    )

    private val mutex = Mutex()
    private var libraryLoaded = false
    private var library: QuoteLibrary? = null
    private var rotation: QuoteRotation? = null
    // Covers duplicate/stale Activity callbacks without growing with lifetime usage.
    private val sessionQuotes = object : LinkedHashMap<String, BreathingQuote>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BreathingQuote>?) = size > 16
    }

    override suspend fun forSession(sessionId: String, isPreview: Boolean): BreathingQuote? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext null
        mutex.withLock {
            if (!libraryLoaded) {
                library = try { loadLibrary() } catch (_: IOException) { null } catch (_: JSONException) { null }
                libraryLoaded = true
            }
            val currentLibrary = library ?: return@withLock null
            if (currentLibrary.quotes.isEmpty()) return@withLock null
            if (isPreview) {
                return@withLock currentLibrary.quotes[Math.floorMod(sessionId.hashCode(), currentLibrary.quotes.size)]
            }
            sessionQuotes[sessionId]?.let { return@withLock it }
            val saved = rotation ?: try { persistence.read() } catch (_: IOException) { QuoteRotation() }
            if (saved.lastSessionId == sessionId && saved.lastSessionQuote != null) {
                sessionQuotes[sessionId] = saved.lastSessionQuote
                rotation = saved
                return@withLock saved.lastSessionQuote
            }
            currentCoroutineContext().ensureActive()
            val next = QuoteRotationSelector.next(currentLibrary, saved, sessionId, random)
            rotation = next
            val quote = checkNotNull(next.lastSessionQuote)
            sessionQuotes[sessionId] = quote
            // DataStore's update is atomic. If disk is unavailable, rotation still works in memory.
            // A cancelled request is rethrown by the coroutine; a committed session is idempotent.
            try { persistence.write(next) } catch (_: IOException) { /* Keep the in-process rotation. */ }
            quote
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            rotation = QuoteRotation()
            sessionQuotes.clear()
            persistence.write(QuoteRotation())
        }
    }

    companion object { const val QUOTE_ASSET_PATH = "quotes/zenquotes-v1.json" }
}

internal data class QuoteLibrary(val version: String, val quotes: List<BreathingQuote>)

/** Defensive runtime reader; the build/import validator additionally rejects any bad row. */
internal object QuoteLibraryCodec {
    fun decode(json: String): QuoteLibrary? = try {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion") != 1 || root.optString("source") != "ZenQuotes" ||
            root.optString("sourceUrl") != ZEN_QUOTES_SOURCE_URL || root.optString("collectionVersion").isBlank()) null
        else {
            val rows = root.optJSONArray("quotes") ?: JSONArray()
            val ids = mutableSetOf<String>()
            val texts = mutableSetOf<String>()
            val quotes = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val quote = decodeQuote(row) ?: return@mapNotNull null
                // Same text with a conflicting author is still a duplicate, never two display records.
                val textKey = quote.text.lowercase(Locale.ROOT)
                if (!ids.add(quote.id) || !texts.add(textKey)) null else quote
            }
            QuoteLibrary(root.getString("collectionVersion"), quotes)
        }
    } catch (_: JSONException) { null }

    internal fun decodeQuote(row: JSONObject): BreathingQuote? {
        val id = row.opt("id") as? String ?: return null
        val text = normalize(row.opt("text") as? String ?: return null)
        val author = normalize(row.opt("author") as? String ?: return null)
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,128}")) || text.isBlank() || author.isBlank() ||
            text.codePointCount(0, text.length) > 180 || author.codePointCount(0, author.length) > 100 ||
            '<' in text || '>' in text || '<' in author || '>' in author ||
            text.any { Character.isISOControl(it) } || author.any { Character.isISOControl(it) }) return null
        return BreathingQuote(id, text, author)
    }

    private fun normalize(value: String): String {
        val entities = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
            "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–")
        // One pass prevents turning doubly encoded text into executable/hidden markup.
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(value) { match ->
            val entity = match.groupValues[1]
            val number = when {
                entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                else -> null
            }
            if (number != null && Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF) String(Character.toChars(number))
            else entities[entity] ?: match.value
        }.replace(Regex("\\s+"), " ").trim()
    }
}

internal data class QuoteRotation(
    val seenIds: Set<String> = emptySet(),
    val remainingIds: List<String> = emptyList(),
    val lastQuoteId: String? = null,
    val lastSessionId: String? = null,
    val lastSessionQuote: BreathingQuote? = null,
)

internal object QuoteRotationSelector {
    fun next(library: QuoteLibrary, saved: QuoteRotation, sessionId: String, random: Random): QuoteRotation {
        require(library.quotes.isNotEmpty())
        val byId = library.quotes.associateBy { it.id }
        var seen = saved.seenIds.intersect(byId.keys)
        val remaining = saved.remainingIds.filter { it in byId && it !in seen }.distinct()
        val additions = byId.keys.filter { it !in seen && it !in remaining }
        var queue = (remaining + additions).toMutableList()
        // New or changed collections can introduce more choices into the unfinished cycle.
        if (additions.isNotEmpty()) queue.shuffle(random)
        if (queue.isEmpty()) {
            seen = emptySet()
            queue = byId.keys.shuffled(random).toMutableList()
        }
        if (queue.size > 1 && queue.first() == saved.lastQuoteId) {
            val alternate = queue.indexOfFirst { it != saved.lastQuoteId }
            val first = queue[0]; queue[0] = queue[alternate]; queue[alternate] = first
        }
        val chosen = queue.removeAt(0)
        return QuoteRotation(seen + chosen, queue, chosen, sessionId, byId.getValue(chosen))
    }
}

internal interface QuoteRotationPersistence {
    suspend fun read(): QuoteRotation
    suspend fun write(value: QuoteRotation)
}

private class DataStoreQuoteRotation(context: Context, scope: CoroutineScope, storeName: String) : QuoteRotationPersistence {
    private val key = stringPreferencesKey("rotation")
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile(storeName) },
    )
    override suspend fun read(): QuoteRotation = QuoteRotationCodec.decode(store.data.first()[key])
    override suspend fun write(value: QuoteRotation) { store.edit { it[key] = QuoteRotationCodec.encode(value) } }
}

internal object QuoteRotationCodec {
    fun encode(value: QuoteRotation): String = JSONObject().apply {
        put("seen", JSONArray(value.seenIds.toList()))
        put("remaining", JSONArray(value.remainingIds))
        put("lastQuoteId", value.lastQuoteId)
        put("lastSessionId", value.lastSessionId)
        value.lastSessionQuote?.let { put("snapshot", JSONObject().put("id", it.id).put("text", it.text).put("author", it.author)) }
    }.toString()

    fun decode(json: String?): QuoteRotation = try {
        if (json == null) QuoteRotation() else {
            val root = JSONObject(json)
            fun ids(name: String): List<String> {
                val rows = root.optJSONArray(name) ?: return emptyList()
                return (0 until rows.length().coerceAtMost(5_000)).mapNotNull { rows.opt(it) as? String }.distinct()
            }
            QuoteRotation(
                seenIds = ids("seen").toSet(), remainingIds = ids("remaining"),
                lastQuoteId = root.opt("lastQuoteId") as? String,
                lastSessionId = root.opt("lastSessionId") as? String,
                lastSessionQuote = root.optJSONObject("snapshot")?.let(QuoteLibraryCodec::decodeQuote),
            )
        }
    } catch (_: JSONException) { QuoteRotation() }
}
