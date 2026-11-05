package com.pause.domain

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A single in-memory intervention. Restoring a process never silently authorizes another app. */
class InterventionEngine(
    private val scope: CoroutineScope,
    private val history: HistoryRepository,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    private val mutableState = MutableStateFlow<InterventionSession?>(null)
    val state: StateFlow<InterventionSession?> = mutableState.asStateFlow()
    private val lock = Any()
    private var timer: Job? = null
    private var deadlineMillis = 0L
    private var completedAtMillis: Long? = null
    private var usageStartedAtMillis: Long? = null
    private var usageLastSampleMillis = 0L
    private var chargingUsage = false
    private var lastPersistedUsedMillis = 0L
    private sealed interface HistoryWrite {
        data class Save(val record: InterventionRecord) : HistoryWrite
        data class Flush(val completion: CompletableDeferred<Unit>) : HistoryWrite
    }
    private val historyWrites = Channel<HistoryWrite>(Channel.UNLIMITED)
    private val mutableHistoryError = MutableStateFlow<Throwable?>(null)
    /** Non-null while one or more local records have not been saved; protection still operates. */
    val historyError: StateFlow<Throwable?> = mutableHistoryError.asStateFlow()

    init {
        // FIFO preserves begin/finish order even if the database is opening or the UI acts quickly.
        val writer = scope.launch {
            // A later complete record supersedes a failed earlier write for the same attempt.
            val pending = linkedMapOf<String, InterventionRecord>()
            var inFlightFlush: CompletableDeferred<Unit>? = null
            suspend fun persistPending(): Throwable? {
                var failure: Throwable? = null
                val entries = pending.entries.iterator()
                while (entries.hasNext()) {
                    val record = entries.next().value
                    try {
                        history.upsert(record)
                        entries.remove()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error
                    }
                }
                mutableHistoryError.value = failure
                return failure
            }
            try {
                for (write in historyWrites) when (write) {
                    is HistoryWrite.Save -> {
                        pending[write.record.id] = write.record
                        persistPending()
                    }
                    is HistoryWrite.Flush -> {
                        inFlightFlush = write.completion
                        val failure = persistPending()
                        if (failure == null) write.completion.complete(Unit)
                        else write.completion.completeExceptionally(failure)
                        inFlightFlush = null
                    }
                }
            } finally {
                inFlightFlush?.completeExceptionally(CancellationException("History writer stopped"))
            }
        }
        writer.invokeOnCompletion { cause ->
            val failure = cause ?: IllegalStateException("History writer stopped")
            mutableHistoryError.value = failure
            historyWrites.close(failure)
            while (true) {
                val pending = historyWrites.tryReceive().getOrNull() ?: break
                if (pending is HistoryWrite.Flush) pending.completion.completeExceptionally(failure)
            }
        }
    }

    /** Call before deleting history so queued session writes cannot repopulate a reset database. */
    suspend fun flushHistory() {
        val completion = CompletableDeferred<Unit>()
        historyWrites.send(HistoryWrite.Flush(completion))
        completion.await()
    }

    fun begin(target: InstalledApp, config: InterventionConfig, isPreview: Boolean = false): Boolean =
        synchronized(lock) {
            val existing = mutableState.value
            if (existing != null && existing.target.packageName == target.packageName &&
                existing.isPreview == isPreview &&
                existing.phase in setOf(InterventionPhase.BREATHING, InterventionPhase.DECISION, InterventionPhase.SELECTION)) {
                return@synchronized false
            }
            if (existing?.phase == InterventionPhase.USING) updateUsageLocked()
            if (mutableState.value != null) finishLocked(InterventionOutcome.INTERRUPTED)
            beginLocked(target, config.normalized(), isPreview)
            true
        }

    private fun beginLocked(target: InstalledApp, config: InterventionConfig, isPreview: Boolean) {
        timer?.cancel()
        completedAtMillis = null
        usageStartedAtMillis = null
        chargingUsage = false
        lastPersistedUsedMillis = 0
        val session = InterventionSession(
            id = UUID.randomUUID().toString(), target = target, config = config,
            phase = InterventionPhase.BREATHING, selectedSeconds = config.startUsageSeconds,
            remainingMillis = config.breathingSeconds * 1000L,
            startedAtMillis = clock.epochMillis(), isPreview = isPreview,
        )
        mutableState.value = session
        save(session)
        startTimerLocked(session.remainingMillis)
    }

    /** Finishing a breath only offers a choice; entering duration selection requires this action. */
    fun continueToSelection(): Boolean = synchronized(lock) {
        val current = mutableState.value ?: return@synchronized false
        if (current.phase != InterventionPhase.DECISION) return@synchronized false
        mutableState.value = current.copy(phase = InterventionPhase.SELECTION)
        true
    }

    fun selectDuration(seconds: Int) = synchronized(lock) {
        val current = mutableState.value ?: return@synchronized
        if (current.phase != InterventionPhase.SELECTION) return@synchronized
        mutableState.value = current.copy(
            selectedSeconds = seconds.coerceIn(current.config.minUsageSeconds, current.config.maxUsageSeconds),
        )
    }

    /** A delayed presentation must still show the complete breath, never reset a running allowance. */
    fun restartBreathingForPresentation() = synchronized(lock) {
        val current = mutableState.value ?: return@synchronized
        if (current.phase !in setOf(InterventionPhase.BREATHING, InterventionPhase.DECISION, InterventionPhase.SELECTION)) return@synchronized
        val next = current.copy(
            phase = InterventionPhase.BREATHING,
            selectedSeconds = current.config.startUsageSeconds,
            remainingMillis = current.config.breathingSeconds * 1000L,
        )
        mutableState.value = next
        save(next)
        startTimerLocked(next.remainingMillis)
    }

    /**
     * Explicitly selects an allowance. No usage is charged until the target is observed foreground.
     * A preview returns the selected session but clears itself without launching or recording usage.
     */
    fun requestUsage(): InterventionSession? = synchronized(lock) {
        val current = mutableState.value ?: return@synchronized null
        if (current.phase != InterventionPhase.SELECTION) return@synchronized null
        val selected = current.selectedSeconds.coerceIn(current.config.minUsageSeconds, current.config.maxUsageSeconds)
        val launching = current.copy(
            phase = InterventionPhase.LAUNCHING, selectedSeconds = selected,
            remainingMillis = selected * 1000L, usedMillis = 0,
        )
        if (current.isPreview) {
            timer?.cancel()
            timer = null
            mutableState.value = null
            return@synchronized launching
        }
        completedAtMillis = clock.epochMillis()
        mutableState.value = launching
        save(launching)
        startTimerLocked(LAUNCH_TIMEOUT_MILLIS)
        launching
    }

    /**
     * Only the Accessibility adapter decides which package is genuinely foreground.
     * Keyboards are transient and count as continued app use. Covering system dialogs pause
     * charging without renewing the allowance. A real app departure discards all remaining time.
     */
    fun onForeground(packageName: String, isTransient: Boolean = false, obscured: Boolean = false) =
        synchronized(lock) {
            val before = mutableState.value ?: return@synchronized
            if (before.phase == InterventionPhase.LAUNCHING) {
                if (!isTransient && !obscured && packageName == before.target.packageName) {
                    timer?.cancel()
                    chargingUsage = true
                    usageLastSampleMillis = clock.elapsedRealtimeMillis()
                    usageStartedAtMillis = clock.epochMillis()
                    mutableState.value = before.copy(phase = InterventionPhase.USING)
                    save(requireNotNull(mutableState.value))
                    startTimerLocked(before.selectedSeconds * 1000L)
                }
                return@synchronized
            }
            if (before.phase != InterventionPhase.USING) return@synchronized
            updateUsageLocked()
            val current = mutableState.value ?: return@synchronized
            if (current.id != before.id) {
                // Expiry won a near-boundary race. Never leave its renewal over another app.
                if (!isTransient && packageName != before.target.packageName) {
                    finishLocked(InterventionOutcome.INTERRUPTED)
                }
                return@synchronized
            }
            when {
                obscured -> chargingUsage = false
                isTransient -> Unit
                packageName == current.target.packageName -> chargingUsage = true
                else -> finishLocked(InterventionOutcome.LEFT_APP)
            }
        }

    /** Cancellation/revocation/lock may end a session, but cannot grant unlimited admission. */
    fun finish(outcome: InterventionOutcome): InterventionSession? = synchronized(lock) {
        if (outcome == InterventionOutcome.CONTINUED || outcome == InterventionOutcome.EXPIRED) {
            return@synchronized null
        }
        val before = mutableState.value ?: return@synchronized null
        if (before.phase == InterventionPhase.USING) updateUsageLocked()
        val current = mutableState.value ?: return@synchronized null
        if (current.id != before.id) {
            // A concurrent expiry already finalized the old session; terminate only its renewal.
            finishLocked(InterventionOutcome.INTERRUPTED)
            return@synchronized before
        }
        finishLocked(outcome)
    }

    private fun finishLocked(outcome: InterventionOutcome): InterventionSession? {
        val current = mutableState.value ?: return null
        timer?.cancel()
        timer = null
        chargingUsage = false
        mutableState.value = null
        save(current, outcome)
        return current
    }

    private fun save(session: InterventionSession, outcome: InterventionOutcome? = null) {
        if (session.isPreview) return
        val hasAllowance = completedAtMillis != null
        val queued = historyWrites.trySend(HistoryWrite.Save(
            InterventionRecord(
                id = session.id, packageName = session.target.packageName, appName = session.target.name,
                startedAtMillis = session.startedAtMillis,
                finishedAtMillis = if (outcome == null) null else clock.epochMillis(),
                outcome = outcome, waitSeconds = 0, completedAtMillis = completedAtMillis,
                kind = RecordKind.USAGE,
                allocatedSeconds = if (hasAllowance) session.selectedSeconds else 0,
                usedMillis = session.usedMillis.coerceIn(0, session.selectedSeconds * 1000L),
                usageStartedAtMillis = usageStartedAtMillis,
            ),
        ))
        if (queued.isFailure) {
            mutableHistoryError.value = queued.exceptionOrNull() ?: IllegalStateException("History write queue is unavailable")
        }
    }

    private fun updateUsageLocked() {
        val current = mutableState.value ?: return
        if (current.phase != InterventionPhase.USING) return
        val now = clock.elapsedRealtimeMillis()
        val elapsed = (now - usageLastSampleMillis).coerceAtLeast(0)
        usageLastSampleMillis = now
        if (!chargingUsage) return
        val total = current.selectedSeconds * 1000L
        val used = (current.usedMillis + elapsed).coerceAtMost(total)
        val next = current.copy(usedMillis = used, remainingMillis = total - used)
        mutableState.value = next
        if (used >= total) {
            // Finalize this generation before creating a new one. Old timer callbacks stop here.
            finishLocked(InterventionOutcome.EXPIRED)
            beginLocked(current.target, current.config, isPreview = false)
        } else if (used - lastPersistedUsedMillis >= 1_000) {
            lastPersistedUsedMillis = used
            save(next)
        }
    }

    private fun startTimerLocked(durationMillis: Long) {
        timer?.cancel()
        deadlineMillis = clock.elapsedRealtimeMillis() + durationMillis
        val sessionId = mutableState.value?.id ?: return
        timer = scope.launch {
            while (isActive) {
                delay(50)
                val finished = synchronized(lock) {
                    val current = mutableState.value
                    if (current == null || current.id != sessionId) return@synchronized true
                    when (current.phase) {
                        InterventionPhase.USING -> {
                            updateUsageLocked()
                            mutableState.value?.id != sessionId
                        }
                        InterventionPhase.BREATHING -> {
                            val remaining = (deadlineMillis - clock.elapsedRealtimeMillis()).coerceAtLeast(0)
                            mutableState.value = current.copy(
                                remainingMillis = remaining,
                                phase = if (remaining == 0L) InterventionPhase.DECISION else InterventionPhase.BREATHING,
                            )
                            remaining == 0L
                        }
                        InterventionPhase.LAUNCHING -> {
                            if (clock.elapsedRealtimeMillis() >= deadlineMillis) {
                                finishLocked(InterventionOutcome.LAUNCH_FAILED)
                                true
                            } else false
                        }
                        InterventionPhase.DECISION, InterventionPhase.SELECTION -> true
                    }
                }
                if (finished) break
            }
        }
    }

    companion object {
        const val LAUNCH_TIMEOUT_MILLIS = 10_000L
    }
}
