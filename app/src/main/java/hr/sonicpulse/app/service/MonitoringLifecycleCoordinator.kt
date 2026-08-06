package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationStartResult
import java.util.concurrent.CancellationException

enum class MonitoringLifecycleState { IDLE, STARTING, ACTIVE, STOPPING }

sealed interface MonitoringLifecycleEffect {
    data class StartLocation(val generation: Long) : MonitoringLifecycleEffect
    data object StartAudioCapture : MonitoringLifecycleEffect
    data class ReportStartupFailure(val failure: MonitoringStartupFailure) : MonitoringLifecycleEffect
    data class StopSession(val wasActive: Boolean) : MonitoringLifecycleEffect
    data object None : MonitoringLifecycleEffect
}

/**
 * Plain-Kotlin state machine, decoupled from Android/AudioRecorder/LocationProvider so it can be
 * unit-tested directly. Guards against the async location-start race: every StartLocation effect
 * carries a generation, and onLocationStartResult only honors a callback whose generation still
 * matches the current one — onStopOrDestroy bumps the generation when interrupting a pending
 * start, so a stale callback (including a location success arriving after the attempt was
 * cancelled) can never resurrect state or start audio capture.
 *
 * [MonitoringLifecycleState.STOPPING] closes a second race: an async teardown (audio/location
 * stop, logger finalize, submission drain) takes real time, and a Start arriving before it
 * finishes must not begin a second, overlapping session. Stop from STARTING or ACTIVE moves here
 * instead of straight to IDLE; a Start received while STOPPING is remembered (at most one — see
 * [onActionStart]) rather than acted on immediately, and is only actually started, with a fresh
 * generation, once [onTeardownComplete] confirms the previous session's teardown has genuinely
 * finished. This is what lets the actual async work (in [MonitoringSessionRunner]) safely operate
 * on "the session I was asked to tear down" without racing whatever session — if any — replaces it.
 */
class MonitoringLifecycleCoordinator {

    var state: MonitoringLifecycleState = MonitoringLifecycleState.IDLE
        private set

    private var generation = 0L
    private var restartPending = false

    fun onActionStart(): MonitoringLifecycleEffect = when (state) {
        MonitoringLifecycleState.IDLE -> {
            state = MonitoringLifecycleState.STARTING
            generation++
            MonitoringLifecycleEffect.StartLocation(generation)
        }
        // Remembered, not acted on: the previous session's teardown may still be reading/closing
        // resources a new session must not touch yet. onTeardownComplete() starts it once that
        // teardown genuinely finishes. Repeated Starts here collapse into the same single pending
        // restart — never queued more than once.
        MonitoringLifecycleState.STOPPING -> {
            restartPending = true
            MonitoringLifecycleEffect.None
        }
        MonitoringLifecycleState.STARTING, MonitoringLifecycleState.ACTIVE -> MonitoringLifecycleEffect.None
    }

    fun onLocationStartResult(forGeneration: Long, result: LocationStartResult): MonitoringLifecycleEffect {
        if (state != MonitoringLifecycleState.STARTING || forGeneration != generation) {
            return MonitoringLifecycleEffect.None
        }
        return when (result) {
            LocationStartResult.Started -> {
                state = MonitoringLifecycleState.ACTIVE
                MonitoringLifecycleEffect.StartAudioCapture
            }
            LocationStartResult.PermissionDenied -> {
                state = MonitoringLifecycleState.IDLE
                MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationPermissionDenied)
            }
            LocationStartResult.LocationServicesDisabled -> {
                state = MonitoringLifecycleState.IDLE
                MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationServicesDisabled)
            }
            is LocationStartResult.Failed -> {
                state = MonitoringLifecycleState.IDLE
                MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationStartFailed(result.cause))
            }
            LocationStartResult.Cancelled -> {
                // Reaching this branch at all means the guard above already confirmed we're
                // genuinely still STARTING for the current generation — a Cancelled reported
                // for our own stop() would have arrived after onStopOrDestroy() already moved
                // state off STARTING and bumped the generation, so it would never reach here
                // (the guard would have already returned None). A Cancelled that DOES reach
                // this point is therefore always a genuine, unexpected termination (e.g. Play
                // Services itself cancelling the underlying Task) and must be treated as a
                // real startup failure — never silently ignored, which would leave the service
                // stuck in STARTING forever.
                state = MonitoringLifecycleState.IDLE
                MonitoringLifecycleEffect.ReportStartupFailure(
                    MonitoringStartupFailure.LocationStartFailed(CancellationException("Location start was cancelled"))
                )
            }
        }
    }

    /**
     * A synchronous startup-gate failure (permission/location-services/foreground promotion) —
     * nothing async was ever started (no location request, no audio), so there is nothing to
     * tear down and this goes straight back to IDLE, never through STOPPING. A no-op from any
     * state other than STARTING.
     */
    fun onSynchronousStartupAbort(): MonitoringLifecycleEffect {
        if (state != MonitoringLifecycleState.STARTING) return MonitoringLifecycleEffect.None
        state = MonitoringLifecycleState.IDLE
        return MonitoringLifecycleEffect.None
    }

    /**
     * Covers ACTION_STOP, onDestroy, a capture-thread failure, and a processing failure alike —
     * all mean "tear down whatever session is in progress, if any," via STOPPING rather than
     * straight to IDLE, so a Start arriving before that teardown actually finishes is queued (see
     * [onActionStart]) instead of racing it. Idempotent while already STOPPING: a repeat call
     * (e.g. onDestroy() arriving during an explicit Stop's own teardown) neither starts a second
     * teardown nor leaves [state] unaffected — it does clear any not-yet-applied pending restart,
     * since a Stop always has the final word over a request to start again that hasn't happened
     * yet.
     */
    fun onStopOrDestroy(): MonitoringLifecycleEffect = when (state) {
        MonitoringLifecycleState.STARTING -> {
            generation++ // invalidate any pending location-start callback
            state = MonitoringLifecycleState.STOPPING
            restartPending = false
            MonitoringLifecycleEffect.StopSession(wasActive = false)
        }
        MonitoringLifecycleState.ACTIVE -> {
            state = MonitoringLifecycleState.STOPPING
            restartPending = false
            MonitoringLifecycleEffect.StopSession(wasActive = true)
        }
        MonitoringLifecycleState.STOPPING -> {
            restartPending = false
            MonitoringLifecycleEffect.None
        }
        MonitoringLifecycleState.IDLE -> MonitoringLifecycleEffect.None
    }

    /**
     * Called exactly once, by whichever teardown currently owns STOPPING, once every teardown
     * step has genuinely finished. Returns a fresh [MonitoringLifecycleEffect.StartLocation] if a
     * restart was queued while tearing down (exactly the same shape [onActionStart] itself
     * returns for a brand new Start — a queued restart is otherwise indistinguishable from one),
     * or [MonitoringLifecycleEffect.None] if the session should simply end. A no-op (also
     * [MonitoringLifecycleEffect.None], state left untouched) if called from any state other than
     * STOPPING — defensive: the only caller is the one owned teardown job, which should only ever
     * reach this point once, while still STOPPING.
     */
    fun onTeardownComplete(): MonitoringLifecycleEffect {
        if (state != MonitoringLifecycleState.STOPPING) return MonitoringLifecycleEffect.None
        return if (restartPending) {
            restartPending = false
            state = MonitoringLifecycleState.STARTING
            generation++
            MonitoringLifecycleEffect.StartLocation(generation)
        } else {
            state = MonitoringLifecycleState.IDLE
            MonitoringLifecycleEffect.None
        }
    }

    /**
     * Called instead of [onTeardownComplete] when the owned teardown itself threw rather than
     * finishing normally — a broken teardown must never be treated as a success (which could
     * start a queued restart on top of resources that were never actually released) but must
     * also never leave [state] wedged in STOPPING forever. Always resolves straight to IDLE and
     * discards any queued restart — the caller ([MonitoringSessionRunner]) is responsible for
     * reporting the failure and driving the service's normal idle/failure cleanup path; this only
     * updates the state machine. A no-op from any state other than STOPPING, for the same
     * defensive reason as [onTeardownComplete].
     */
    fun onTeardownFailed() {
        if (state != MonitoringLifecycleState.STOPPING) return
        restartPending = false
        state = MonitoringLifecycleState.IDLE
    }
}
