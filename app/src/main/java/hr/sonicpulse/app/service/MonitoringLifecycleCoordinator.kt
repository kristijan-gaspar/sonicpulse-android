package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationStartResult
import java.util.concurrent.CancellationException

enum class MonitoringLifecycleState { IDLE, STARTING, ACTIVE }

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
 */
class MonitoringLifecycleCoordinator {

    var state: MonitoringLifecycleState = MonitoringLifecycleState.IDLE
        private set

    private var generation = 0L

    fun onActionStart(): MonitoringLifecycleEffect {
        if (state != MonitoringLifecycleState.IDLE) {
            return MonitoringLifecycleEffect.None
        }
        state = MonitoringLifecycleState.STARTING
        generation++
        return MonitoringLifecycleEffect.StartLocation(generation)
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

    /** Covers ACTION_STOP, onDestroy, and an audio-capture runtime failure alike — all mean
     * "tear down whatever session is in progress, if any." */
    fun onStopOrDestroy(): MonitoringLifecycleEffect = when (state) {
        MonitoringLifecycleState.STARTING -> {
            generation++ // invalidate any pending location-start callback
            state = MonitoringLifecycleState.IDLE
            MonitoringLifecycleEffect.StopSession(wasActive = false)
        }
        MonitoringLifecycleState.ACTIVE -> {
            state = MonitoringLifecycleState.IDLE
            MonitoringLifecycleEffect.StopSession(wasActive = true)
        }
        MonitoringLifecycleState.IDLE -> MonitoringLifecycleEffect.None
    }
}
