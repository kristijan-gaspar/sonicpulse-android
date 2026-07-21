package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel

/** Why monitoring startup could not proceed. Distinct from AudioCaptureError, which covers
 * runtime failures of an already-running capture session, not startup gating. */
sealed interface MonitoringStartupFailure {
    data object MicrophonePermissionDenied : MonitoringStartupFailure
    data object LocationPermissionDenied : MonitoringStartupFailure
    data class ForegroundStartFailed(val cause: Throwable) : MonitoringStartupFailure
}

sealed interface MonitoringStartupResult {
    data object Proceed : MonitoringStartupResult
    data class Failed(val failure: MonitoringStartupFailure) : MonitoringStartupResult
}

/** Outcome of attempting to promote the service to the foreground. */
sealed interface ForegroundStartOutcome {
    data object Started : ForegroundStartOutcome
    /** The OS rejected the promotion specifically due to a missing/denied permission (RECORD_AUDIO or location). */
    data object PermissionDenied : ForegroundStartOutcome
    /** The OS rejected the promotion for a reason unrelated to permissions (e.g. app state). */
    data class Failed(val cause: Throwable) : ForegroundStartOutcome
}

/**
 * Decides whether it's safe to start audio capture and location updates. Both RECORD_AUDIO and
 * location permission are checked before attempting to promote the service to the foreground,
 * and again after — Android 14+ validates permissions again when promoting a foreground service,
 * so [startForeground] itself may fail with [ForegroundStartOutcome.PermissionDenied] even when
 * the earlier checks passed. In that case, the gate re-checks both permissions itself to
 * attribute the failure to the one that's actually missing, rather than guessing.
 * [ForegroundStartOutcome.Failed] (e.g. ForegroundServiceStartNotAllowedException) is a distinct,
 * non-permission failure and must never be reported as either permission being denied.
 */
class MonitoringStartupGate(
    private val hasRecordAudioPermission: () -> Boolean,
    private val locationPermissionLevel: () -> LocationPermissionLevel,
    private val startForeground: () -> ForegroundStartOutcome
) {
    fun attemptStartup(): MonitoringStartupResult {
        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied)
        }
        if (locationPermissionLevel() == LocationPermissionLevel.NONE) {
            return MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied)
        }

        return when (val outcome = startForeground()) {
            is ForegroundStartOutcome.Failed ->
                MonitoringStartupResult.Failed(MonitoringStartupFailure.ForegroundStartFailed(outcome.cause))
            ForegroundStartOutcome.PermissionDenied ->
                MonitoringStartupResult.Failed(attributePermissionDenial())
            ForegroundStartOutcome.Started -> {
                if (!hasRecordAudioPermission()) {
                    MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied)
                } else if (locationPermissionLevel() == LocationPermissionLevel.NONE) {
                    MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied)
                } else {
                    MonitoringStartupResult.Proceed
                }
            }
        }
    }

    private fun attributePermissionDenial(): MonitoringStartupFailure =
        if (!hasRecordAudioPermission()) {
            MonitoringStartupFailure.MicrophonePermissionDenied
        } else {
            MonitoringStartupFailure.LocationPermissionDenied
        }
}
