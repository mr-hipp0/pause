package com.pause.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the packaged collection and real DataStore; the target is the isolated data test APK. */
@RunWith(AndroidJUnit4::class)
class OfflineQuotePersistenceTest {
    @Test fun bundledQuotesPersistAcrossRepositoryRecreationAndPreviewsDoNotConsumeRotation() = runBlocking {
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue("Never modify the user's application state", context.packageName.endsWith(".test"))
            val storeName = "quote-test-${UUID.randomUUID()}"
            val stateFile = context.preferencesDataStoreFile(storeName)
            val library = context.assets.open(OfflineQuoteRepository.QUOTE_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { QuoteLibraryCodec.decode(it.readText()) }
            assertNotNull("The release collection must be packaged and valid", library)
            val quotes = requireNotNull(library).quotes
            assertTrue("The real collection must contain usable attributed quotes", quotes.isNotEmpty())
            assertTrue(quotes.all { it.text.isNotBlank() && it.author.isNotBlank() })
            assertEquals(quotes.size, quotes.map { it.id }.distinct().size)

            var owner = SupervisorJob()
            try {
                var repository = OfflineQuoteRepository(context, CoroutineScope(owner + Dispatchers.IO), storeName)
                val first = requireNotNull(repository.forSession("persistent-attempt"))
                assertTrue(quotes.contains(first))
                assertEquals(first, repository.forSession("persistent-attempt"))

                val committedState = stateFile.readBytes()
                val preview = repository.forSession("preview-attempt", isPreview = true)
                assertNotNull(preview)
                assertEquals(preview, repository.forSession("preview-attempt", isPreview = true))
                repeat(5) { repository.forSession("other-preview-$it", isPreview = true) }
                assertArrayEquals("Preview must not write real rotation progress", committedState, stateFile.readBytes())

                // Await scope completion so the previous DataStore has released this exact file.
                owner.cancelAndJoin()
                owner = SupervisorJob()
                repository = OfflineQuoteRepository(context, CoroutineScope(owner + Dispatchers.IO), storeName)
                assertEquals("Restore both quote text and author for the same attempt", first,
                    repository.forSession("persistent-attempt"))

                val seen = mutableSetOf(first.id)
                var previous = first
                repeat(quotes.size - 1) { index ->
                    val next = requireNotNull(repository.forSession("real-attempt-$index"))
                    assertTrue("No repeats before the full collection is exhausted", seen.add(next.id))
                    assertTrue(quotes.contains(next))
                    previous = next
                }
                assertEquals(quotes.size, seen.size)
                val nextCycle = requireNotNull(repository.forSession("next-cycle"))
                if (quotes.size > 1) assertNotEquals("Avoid a repeat at the cycle boundary", previous.id, nextCycle.id)

                repository.clear()
                owner.cancelAndJoin()
                owner = SupervisorJob()
                val resetStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(owner + Dispatchers.IO), produceFile = { stateFile },
                )
                val reset = QuoteRotationCodec.decode(resetStore.data.first()[stringPreferencesKey("rotation")])
                assertTrue(reset.seenIds.isEmpty())
                assertTrue(reset.remainingIds.isEmpty())
                assertEquals(null, reset.lastSessionQuote)
                assertEquals(null, reset.lastSessionId)
            } finally {
                owner.cancelAndJoin()
                if (stateFile.exists()) assertTrue("Remove isolated fixture state", stateFile.delete())
            }
        }
    }
}
