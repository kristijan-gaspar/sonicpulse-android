package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Testing-only, event-centric diagnostic log of one monitoring session — not general application
 * logging (see the observability package). [JsonDetectionSessionLogger] and [NoOpDetectionSessionLogger]
 * are selected once, in `di/ObservabilityModule`, behind `BuildConfig.ENABLE_SESSION_LOGGING` — no
 * caller of this interface (in particular [hr.sonicpulse.app.service.MonitoringService]) branches
 * on that flag itself.
 *
 * Threading contract: [onBlock] is called synchronously from the audio capture thread and must
 * stay cheap (no I/O, no JSON, no unbounded allocation) — see [JsonDetectionSessionLogger]'s KDoc
 * for how it stays cheap. [startSession] and [finishSession] are called from the service's own
 * (main) thread. [exportJson] is called from whatever dispatcher the caller chooses (expected to
 * be off the audio thread) — implementations must not assume a specific one.
 */
interface DetectionSessionLogger {

    /** True once a finished session is available to export — false again once a *new* session is
     * genuinely activated by its first [onBlock] call, not merely prepared via [startSession].
     * Never true while a session is actively being recorded. */
    val hasCompletedSession: StateFlow<Boolean>

    /** Prepares a new session (captures config/device identity) and discards any still-in-progress
     * *activated* session's buffered data. Deliberately does not yet touch the previous completed
     * session — a capture attempt that never produces a single block (e.g. a synchronous
     * `AudioRecord` failure) must not destroy the last export. The previous completed session is
     * only discarded once this prepared session is genuinely activated — see [onBlock]. */
    fun startSession(config: EngineConfig)

    /** [event] is non-null exactly on the block where the engine actually emitted a
     * [DetectionEvent] — see [FinalizedEvent]. Must be safe to call before [startSession] (a
     * no-op) and safe to call many times per second. The *first* call after a [startSession]
     * genuinely activates that session (see implementations for what that means). */
    fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?)

    /** Finalizes the in-progress session into the latest completed session, if one was genuinely
     * activated (received at least one [onBlock] call). Idempotent: a no-op when no session is
     * in progress (already finished, never started, or prepared but never activated) — safe to
     * call from more than one teardown path, and safe after a capture attempt that never produced
     * a block (the previous completed session is left untouched in that case). */
    fun finishSession()

    /** The latest completed session as pretty-printed JSON, or null if none exists yet. Safe to
     * call at any time, including while a new session is already in progress (reflects whatever
     * was completed last). */
    fun exportJson(): String?
}

/** Pairs a [DetectionEvent] with the same peak instant [hr.sonicpulse.app.service.MonitoringService]
 * already computes for [hr.sonicpulse.app.domain.model.SessionDetection.peakTimeClient] — the
 * logger never (re)computes detection timing itself, so the two can never drift apart. */
data class FinalizedEvent(val event: DetectionEvent, val peakTimeClient: Instant)
