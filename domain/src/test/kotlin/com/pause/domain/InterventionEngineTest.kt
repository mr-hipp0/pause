package com.pause.domain

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterventionEngineTest {
    private val target = InstalledApp("example.video", "Video", AppCategory.VIDEO)
    private val other = InstalledApp("example.social", "Social", AppCategory.SOCIAL)
    private val config = InterventionConfig(breathingSeconds = 3, minUsageSeconds = 1)

    @Test fun freshAttemptRequiresSelectionAndResetsStartingValue() = runTest {
        val engine = engine()
        choose(engine)
        engine.selectDuration(120); engine.requestUsage(); engine.onForeground(target.packageName)
        advanceTimeBy(5_000); runCurrent()
        engine.onForeground("example.home")
        assertNull(engine.state.value)
        choose(engine)
        assertEquals(15, engine.state.value?.selectedSeconds)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(InterventionPhase.SELECTION, engine.state.value?.phase)
    }

    @Test fun grantStartsChargingOnlyAfterTargetConfirmedForeground() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine); engine.selectDuration(5)
        assertEquals(target, engine.requestUsage()?.target)
        assertEquals(InterventionPhase.LAUNCHING, engine.state.value?.phase)
        engine.onForeground("example.home")
        advanceTimeBy(9_000); runCurrent()
        assertEquals(5_000L, engine.state.value?.remainingMillis)
        assertEquals(0L, engine.state.value?.usedMillis)
        assertNull(history.records.value.single().usageStartedAtMillis)
        engine.onForeground(target.packageName)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(InterventionPhase.USING, engine.state.value?.phase)
        assertEquals(1_000L, engine.state.value?.usedMillis)
        assertNotNull(history.records.value.single().usageStartedAtMillis)
        assertEquals(RecordKind.USAGE, history.records.value.single().kind)
    }

    @Test fun threeConsecutiveExpirationsEachStartFreshBreathingAndSelection() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        engine.begin(target, config.copy(startUsageSeconds = 1))
        val ids = mutableSetOf<String>()
        repeat(3) {
            val id = requireNotNull(engine.state.value).id
            assertTrue(ids.add(id))
            advanceTimeBy(3_000); runCurrent()
            assertEquals(InterventionPhase.SELECTION, engine.state.value?.phase)
            engine.requestUsage(); engine.onForeground(target.packageName)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
            assertNotEquals(id, engine.state.value?.id)
            assertEquals(1, engine.state.value?.selectedSeconds)
        }
        val expired = history.records.value.filter { it.outcome == InterventionOutcome.EXPIRED }
        assertEquals(3, expired.size)
        assertTrue(expired.all { it.allocatedSeconds == 1 && it.usedMillis == 1_000L })
        assertTrue(expired.all { it.waitSeconds == 0 && it.kind == RecordKind.USAGE })
    }

    @Test fun exactFiveMinuteAllowanceExpiresAtItsForegroundDeadline() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine, config.copy(maxUsageSeconds = Int.MAX_VALUE))
        engine.selectDuration(Int.MAX_VALUE)
        assertEquals(300, engine.requestUsage()?.selectedSeconds)
        engine.onForeground(target.packageName)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(InterventionPhase.USING, engine.state.value?.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
        assertEquals(300_000L, history.records.value.single { it.outcome == InterventionOutcome.EXPIRED }.usedMillis)
    }

    @Test fun systemOverlayPausesUsageThenSameAppResumesRemainingAllowance() = runTest {
        val engine = engine()
        choose(engine); engine.selectDuration(5); engine.requestUsage(); engine.onForeground(target.packageName)
        val id = engine.state.value?.id
        advanceTimeBy(1_000); runCurrent()
        engine.onForeground("com.android.systemui", isTransient = true, obscured = true)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(id, engine.state.value?.id)
        assertEquals(1_000L, engine.state.value?.usedMillis)
        engine.onForeground(target.packageName)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2_000L, engine.state.value?.usedMillis)
    }

    @Test fun keyboardAndDuplicateEventsNeverRenewAllowance() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine); engine.selectDuration(2); engine.requestUsage(); engine.onForeground(target.packageName)
        val id = engine.state.value?.id
        advanceTimeBy(500); runCurrent()
        engine.onForeground("example.keyboard", isTransient = true)
        engine.onForeground(target.packageName); engine.onForeground(target.packageName)
        advanceTimeBy(1_500); runCurrent()
        assertNotEquals(id, engine.state.value?.id)
        assertEquals(InterventionOutcome.EXPIRED, history.records.value.single { it.id == id }.outcome)
    }

    @Test fun leavingEarlyRecordsActualUseAndDiscardsUnusedTime() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine); engine.selectDuration(300); engine.requestUsage(); engine.onForeground(target.packageName)
        val id = engine.state.value?.id
        advanceTimeBy(1_225)
        engine.onForeground("example.home"); runCurrent()
        assertNull(engine.state.value)
        val record = history.records.value.single { it.id == id }
        assertEquals(InterventionOutcome.LEFT_APP, record.outcome)
        assertEquals(1_225L, record.usedMillis)
        assertEquals(300, record.allocatedSeconds)
        engine.begin(target, config)
        assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
        assertEquals(0L, engine.state.value?.usedMillis)
    }

    @Test fun staleTimerCannotExpireAnotherAppOrSession() = runTest {
        val engine = engine()
        choose(engine); engine.selectDuration(5); engine.requestUsage(); engine.onForeground(target.packageName)
        advanceTimeBy(4_000); runCurrent()
        engine.onForeground(other.packageName)
        assertNull(engine.state.value)
        engine.begin(other, config.copy(breathingSeconds = 10))
        val id = engine.state.value?.id
        advanceTimeBy(2_000); runCurrent()
        assertEquals(id, engine.state.value?.id)
        assertEquals(other, engine.state.value?.target)
        assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
    }

    @Test fun launchFailureTimesOutWithoutChargingAnyUsage() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine); engine.requestUsage(); engine.onForeground("example.home")
        advanceTimeBy(InterventionEngine.LAUNCH_TIMEOUT_MILLIS); runCurrent()
        assertNull(engine.state.value)
        val record = history.records.value.single()
        assertEquals(InterventionOutcome.LAUNCH_FAILED, record.outcome)
        assertEquals(0L, record.usedMillis)
        assertNull(record.usageStartedAtMillis)
    }

    @Test fun keyboardOrDialogCannotConfirmTargetLaunch() = runTest {
        val engine = engine()
        choose(engine); engine.requestUsage()
        engine.onForeground(target.packageName, isTransient = true)
        engine.onForeground(target.packageName, obscured = true)
        assertEquals(InterventionPhase.LAUNCHING, engine.state.value?.phase)
        engine.onForeground(target.packageName)
        assertEquals(InterventionPhase.USING, engine.state.value?.phase)
    }

    @Test fun startCannotSkipBreathingOrExtendExistingUsage() = runTest {
        val engine = engine()
        engine.begin(target, config)
        assertNull(engine.requestUsage())
        assertNull(engine.finish(InterventionOutcome.CONTINUED))
        engine.onForeground(target.packageName)
        assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
        advanceTimeBy(3_000); runCurrent()
        assertNotNull(engine.requestUsage())
        assertNull(engine.requestUsage())
        engine.onForeground(target.packageName)
        assertNull(engine.requestUsage())
        assertNull(engine.finish(InterventionOutcome.CONTINUED))
    }

    @Test fun duplicateBreathingEventsKeepOriginalAttemptAndDeadline() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        assertTrue(engine.begin(target, config))
        val id = engine.state.value?.id
        advanceTimeBy(1_000)
        assertFalse(engine.begin(target, config)); runCurrent()
        assertEquals(id, engine.state.value?.id)
        assertEquals(1, history.records.value.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(InterventionPhase.SELECTION, engine.state.value?.phase)
    }

    @Test fun previewEndsAtSelectionWithoutLaunchingOrWritingHistory() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        engine.begin(target, config, isPreview = true)
        advanceTimeBy(3_000); runCurrent()
        val selected = engine.requestUsage()
        assertTrue(requireNotNull(selected).isPreview)
        assertEquals(InterventionPhase.LAUNCHING, selected.phase)
        assertNull(engine.state.value)
        engine.onForeground(target.packageName)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(history.records.value.isEmpty())
    }

    @Test fun lateMonotonicTickCannotChargeBeyondAllocatedTime() = runTest {
        var elapsed = 0L
        val history = MemoryHistory()
        val engine = InterventionEngine(backgroundScope, history, object : MonotonicClock {
            override fun elapsedRealtimeMillis() = elapsed
            override fun epochMillis() = 100_000L + elapsed
        })
        engine.begin(target, config)
        elapsed = 4_000; advanceTimeBy(50); runCurrent()
        engine.selectDuration(1); engine.requestUsage(); engine.onForeground(target.packageName)
        elapsed = 100_000; advanceTimeBy(50); runCurrent()
        assertEquals(InterventionPhase.BREATHING, engine.state.value?.phase)
        assertEquals(1_000L, history.records.value.single { it.outcome == InterventionOutcome.EXPIRED }.usedMillis)
    }

    @Test fun lockOrRevocationIncludesFinalSegmentAndEndsAdmission() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine); engine.requestUsage(); engine.onForeground(target.packageName)
        advanceTimeBy(625)
        engine.finish(InterventionOutcome.INTERRUPTED); runCurrent()
        assertNull(engine.state.value)
        assertEquals(625L, history.records.value.single().usedMillis)
        assertEquals(InterventionOutcome.INTERRUPTED, history.records.value.single().outcome)
    }

    @Test fun processRestartNeverRestoresSavedUsageAsAdmission() = runTest {
        val history = MemoryHistory()
        val original = engine(history)
        choose(original); original.requestUsage(); original.onForeground(target.packageName)
        advanceTimeBy(1_000); runCurrent()
        val restarted = engine(history)
        assertNull(restarted.state.value)
        restarted.onForeground(target.packageName)
        assertNull(restarted.state.value)
    }

    @Test fun latePresentationResetsBreathingButCannotResetRunningUsage() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        choose(engine)
        val id = engine.state.value?.id
        engine.restartBreathingForPresentation()
        assertEquals(id, engine.state.value?.id)
        assertEquals(3_000L, engine.state.value?.remainingMillis)
        advanceTimeBy(3_000); runCurrent()
        engine.requestUsage(); engine.onForeground(target.packageName)
        advanceTimeBy(1_000); runCurrent()
        engine.restartBreathingForPresentation()
        assertEquals(InterventionPhase.USING, engine.state.value?.phase)
        assertEquals(1_000L, engine.state.value?.usedMillis)
        assertEquals(1, history.records.value.size)
    }

    @Test fun resetBarrierPreventsQueuedRecordsReappearingAfterClear() = runTest {
        val history = MemoryHistory()
        val engine = engine(history)
        engine.begin(target, config); engine.finish(InterventionOutcome.CANCELLED)
        engine.flushHistory(); history.clear(); runCurrent()
        assertTrue(history.records.value.isEmpty())
    }

    @Test fun historyFailuresAreRetryableAndFlushRejectsInsteadOfHanging() = runTest {
        val memory = MemoryHistory()
        val failure = IOException("Storage unavailable")
        var fail = true
        val storage = object : HistoryRepository by memory {
            override suspend fun upsert(record: InterventionRecord) {
                if (fail) throw failure
                memory.upsert(record)
            }
        }
        val engine = engine(storage)
        engine.begin(target, config); runCurrent()
        assertSame(failure, engine.historyError.value)
        engine.finish(InterventionOutcome.CANCELLED)
        val flushFailure = requireNotNull(runCatching { engine.flushHistory() }.exceptionOrNull())
        assertEquals(failure.javaClass, flushFailure.javaClass)
        assertEquals(failure.message, flushFailure.message)
        assertSame(failure, generateSequence(flushFailure) { it.cause }.last())
        fail = false; engine.flushHistory()
        assertNull(engine.historyError.value)
        assertEquals(InterventionOutcome.CANCELLED, memory.records.value.single().outcome)
    }

    @Test fun cancelledWriterRejectsFlushInsteadOfSuspendingForever() = runTest {
        val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
        val engine = InterventionEngine(CoroutineScope(backgroundScope.coroutineContext + owner), MemoryHistory())
        owner.cancel(); runCurrent()
        assertTrue(runCatching { engine.flushHistory() }.isFailure)
    }

    private fun TestScope.choose(engine: InterventionEngine, settings: InterventionConfig = config) {
        engine.begin(target, settings)
        advanceTimeBy(settings.breathingSeconds * 1000L); runCurrent()
        assertEquals(InterventionPhase.SELECTION, engine.state.value?.phase)
    }

    private fun TestScope.engine(history: HistoryRepository = MemoryHistory()) = InterventionEngine(
        backgroundScope, history, object : MonotonicClock {
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
            override fun epochMillis() = 100_000L + testScheduler.currentTime
        },
    )

    private class MemoryHistory : HistoryRepository {
        override val records = MutableStateFlow<List<InterventionRecord>>(emptyList())
        override suspend fun upsert(record: InterventionRecord) {
            records.value = records.value.filterNot { it.id == record.id } + record
        }
        override suspend fun clear() { records.value = emptyList() }
    }
}

