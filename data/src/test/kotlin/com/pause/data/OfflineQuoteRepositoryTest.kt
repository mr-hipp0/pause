package com.pause.data

import com.pause.domain.BreathingQuote
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class OfflineQuoteRepositoryTest {
    private val quotes = (1..5).map { BreathingQuote("q$it", "A thoughtful choice $it.", "Author $it") }
    private val library = QuoteLibrary("test-v1", quotes)

    @Test fun `every quote appears once per cycle and boundary never repeats`() = runBlocking {
        val repository = repo()
        val shown = (1..25).map { repository.forSession("session-$it")!! }
        shown.chunked(5).forEach { assertEquals(quotes.toSet(), it.toSet()) }
        shown.zipWithNext().forEach { (first, second) -> assertNotEquals(first, second) }
    }

    @Test fun `duplicate concurrent presentation callbacks consume exactly one quote`() = runBlocking {
        val storage = MemoryRotation()
        val repository = repo(storage)
        val shown = (1..40).map { async { repository.forSession("same-session") } }.awaitAll()
        assertEquals(1, shown.toSet().size)
        assertEquals(1, storage.writes)
        assertEquals(1, storage.value.seenIds.size)
    }

    @Test fun `recreated repository restores last session snapshot and remaining cycle`() = runBlocking {
        val storage = MemoryRotation()
        val first = repo(storage).forSession("active")!!
        val recreated = repo(storage)
        assertEquals(first, recreated.forSession("active"))
        val remainder = (1..4).map { recreated.forSession("later-$it")!! }
        assertEquals(quotes.toSet(), (remainder + first).toSet())
        assertEquals(5, storage.writes)
    }

    @Test fun `preview has stable selection and does not write or change real rotation`() = runBlocking {
        val storage = MemoryRotation()
        val repository = repo(storage)
        val preview = repository.forSession("preview", true)
        assertEquals(preview, repository.forSession("preview", true))
        assertEquals(0, storage.writes)
        repository.forSession("real")
        val saved = storage.value
        repeat(10) { repository.forSession("preview-$it", true) }
        assertEquals(saved, storage.value)
        assertEquals(1, storage.writes)
    }

    @Test fun `collection updates reconcile removed and new quotes without repeating unfinished cycle`() {
        val saved = QuoteRotation(setOf("q1", "removed"), listOf("q2", "removed", "q2"), "q1", "old", quotes[0])
        val changed = QuoteLibrary("test-v2", listOf(quotes[0], quotes[1], BreathingQuote("new", "A fresh thought.", "New Author")))
        val first = QuoteRotationSelector.next(changed, saved, "next", Random(1))
        val second = QuoteRotationSelector.next(changed, first, "next-again", Random(1))
        assertEquals(setOf("q2", "new"), setOf(first.lastQuoteId, second.lastQuoteId))
        assertFalse(first.remainingIds.contains("removed"))
        assertFalse(first.seenIds.contains("removed"))
    }

    @Test fun `snapshot keeps its author and text together even when collection changes`() = runBlocking {
        val storage = MemoryRotation()
        val original = repo(storage).forSession("active")!!
        val changed = OfflineQuoteRepository({ QuoteLibrary("v2", listOf(BreathingQuote("new", "A new quote.", "Other Author"))) }, storage, Random(2))
        assertEquals(original, changed.forSession("active"))
    }

    @Test fun `empty and one quote collections behave safely`() = runBlocking {
        val empty = OfflineQuoteRepository({ QuoteLibrary("empty", emptyList()) }, MemoryRotation())
        assertNull(empty.forSession("session"))
        val single = OfflineQuoteRepository({ QuoteLibrary("single", listOf(quotes[0])) }, MemoryRotation())
        repeat(4) { assertEquals(quotes[0], single.forSession("session-$it")) }
    }

    @Test fun `loading errors hide card and cancellation is never swallowed`() = runBlocking {
        assertNull(OfflineQuoteRepository({ throw IOException("missing asset") }, MemoryRotation()).forSession("session"))
        try {
            OfflineQuoteRepository({ throw CancellationException("cancelled") }, MemoryRotation()).forSession("session")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `disk failures retain in-memory rotation and reset clears it`() = runBlocking {
        val storage = MemoryRotation()
        storage.failWrites = true
        val repository = repo(storage)
        val first = repository.forSession("session")
        assertEquals(first, repository.forSession("session"))
        assertNotEquals(first, repository.forSession("second"))
        storage.failWrites = false
        repository.clear()
        assertEquals(QuoteRotation(), storage.value)
        repository.forSession("new-after-reset")
        assertEquals(1, storage.value.seenIds.size)
    }

    @Test fun `codec preserves punctuation decodes entities and excludes invalid or duplicate records`() {
        val json = """{"schemaVersion":1,"collectionVersion":"test","source":"ZenQuotes","sourceUrl":"https://zenquotes.io/","quotes":[
            {"id":"good","text":"Let &ldquo;this&rdquo; be intentional &#x2014; today.","author":"Author &amp; Friend"},
            {"id":"duplicate","text":"Let “this” be intentional — today.","author":"Different Author"},
            {"id":"missing-author","text":"A thought."},
            {"id":"markup","text":"&lt;script&gt;bad&lt;/script&gt;","author":"Author"},
            {"id":"blank","text":"   ","author":"Author"},
            {"id":"bad-type","text":45,"author":"Author"}
        ]}"""
        val actual = QuoteLibraryCodec.decode(json)!!
        assertEquals(listOf(BreathingQuote("good", "Let “this” be intentional — today.", "Author & Friend")), actual.quotes)
        assertNull(QuoteLibraryCodec.decode("not-json"))
        assertNull(QuoteLibraryCodec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
    }

    @Test fun `rotation codec rejects corrupt snapshots and recovers corrupt storage`() {
        val original = QuoteRotation(setOf("q1"), listOf("q2", "q3"), "q1", "active", quotes[0])
        assertEquals(original, QuoteRotationCodec.decode(QuoteRotationCodec.encode(original)))
        assertEquals(QuoteRotation(), QuoteRotationCodec.decode("malformed"))
        val corrupt = """{"seen":["q1","q1",10],"remaining":["q2","q2"],"lastSessionId":"active","snapshot":{"id":"q1","text":"A quote","author":""}}"""
        val loaded = QuoteRotationCodec.decode(corrupt)
        assertEquals(setOf("q1"), loaded.seenIds)
        assertEquals(listOf("q2"), loaded.remainingIds)
        assertNull(loaded.lastSessionQuote)
    }

    private fun repo(storage: MemoryRotation = MemoryRotation()) = OfflineQuoteRepository({ library }, storage, Random(8))

    private class MemoryRotation : QuoteRotationPersistence {
        var value = QuoteRotation()
        var writes = 0
        var failWrites = false
        override suspend fun read() = value
        override suspend fun write(value: QuoteRotation) {
            if (failWrites) throw IOException("disk unavailable")
            this.value = value
            writes++
        }
    }
}
