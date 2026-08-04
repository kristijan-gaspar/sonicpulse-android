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

    /** True once a finished session is available to export — false again the moment a *new*
     * session starts (see [startSession]). Never true while a session is still in progress. */
    val hasCompletedSession: StateFlow<Boolean>

    /** Begins a new session, replacing any still-in-progress session and discarding the
     * previous completed session (if any) — this is the one moment that discard is allowed. */
    fun startSession(config: EngineConfig)

    /** [event] is non-null exactly on the block where the engine actually emitted a
     * [DetectionEvent] — see [FinalizedEvent]. Must be safe to call before [startSession] (a
     * no-op) and safe to call many times per second. */
    fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?)

    /** Finalizes the in-progress session into the latest completed session, if one is in
     * progress. Idempotent: a no-op when no session is in progress (already finished, or never
     * started) — safe to call from more than one teardown path. */
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
