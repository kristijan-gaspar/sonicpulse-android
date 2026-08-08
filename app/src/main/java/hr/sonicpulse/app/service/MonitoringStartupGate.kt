package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel

/** Why monitoring startup could not proceed. Distinct from AudioCaptureError, which covers
 * runtime failures of an already-running capture session, not startup gating. */
sealed interface MonitoringStartupFailure {
    data object MicrophonePermissionDenied : MonitoringStartupFailure
    data object LocationPermissionDenied : MonitoringStartupFailure
    data object LocationServicesDisabled : MonitoringStartupFailure
    data class LocationStartFailed(val cause: Throwable) : MonitoringStartupFailure
    data class ForegroundStartFailed(val cause: Throwable) : MonitoringStartupFailure
}

sealed interface MonitoringStartupResult {
    data object Proceed : MonitoringStartupResult
    data class Failed(val failure: MonitoringStartupFailure) : MonitoringStartupResult
}

/** Outcome of attempting to promote the service to the foreground. */
sealed interface ForegroundStartOutcome {
    data object Started : ForegroundStartOutcome
    /** The OS rejected the promotion with a SecurityException — could be RECORD_AUDIO or
     * location; the gate attributes which one by re-checking both, preserving [cause]. */
    data class PermissionDenied(val cause: SecurityException) : ForegroundStartOutcome
    /** The OS rejected the promotion for a reason unrelated to permissions (e.g. app state). */
    data class Failed(val cause: Throwable) : ForegroundStartOutcome
}

/**
 * Decides whether it's safe to start audio capture and location updates. Checks run in order:
 * RECORD_AUDIO permission, location permission (COARSE or FINE), system location services
 * enabled, then foreground promotion. Only the two permission checks — not the location-services
 * check — re-run once more after a successful [startForeground] call (Android 14+ validates
 * permissions again when promoting a foreground service, so it can fail with
 * [ForegroundStartOutcome.PermissionDenied] even when the earlier checks passed; there is no
 * equivalent re-validation of location services at promotion time). When that SecurityException
 * happens, the gate re-checks both permissions to attribute the failure to the one that's
 * actually missing — if neither explains it, the original SecurityException is preserved as an
 * unattributed [MonitoringStartupFailure.ForegroundStartFailed], never assumed to be a
 * location-permission problem by default. [ForegroundStartOutcome.Failed] (e.g.
 * ForegroundServiceStartNotAllowedException) is always a distinct, non-permission failure.
 */
class MonitoringStartupGate(
    private val hasRecordAudioPermission: () -> Boolean,
    private val locationPermissionLevel: () -> LocationPermissionLevel,
    private val areLocationServicesEnabled: () -> Boolean,
    private val startForeground: () -> ForegroundStartOutcome,
    private val enableLocationForegroundType: () -> ForegroundStartOutcome
) {
    fun attemptStartup(): MonitoringStartupResult {
        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            )
        }

        when (val outcome = startForeground()) {
            is ForegroundStartOutcome.Failed ->
                return MonitoringStartupResult.Failed(
                    MonitoringStartupFailure.ForegroundStartFailed(outcome.cause)
                )

            is ForegroundStartOutcome.PermissionDenied ->
                return MonitoringStartupResult.Failed(
                    attributeSecurityException(outcome.cause)
                )

            ForegroundStartOutcome.Started -> Unit
        }

        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            )
        }

        if (locationPermissionLevel() == LocationPermissionLevel.NONE) {
            return MonitoringStartupResult.Failed(
                MonitoringStartupFailure.LocationPermissionDenied
            )
        }

        if (!areLocationServicesEnabled()) {
            return MonitoringStartupResult.Failed(
                MonitoringStartupFailure.LocationServicesDisabled
            )
        }

        return when (val outcome = enableLocationForegroundType()) {
            is ForegroundStartOutcome.Failed ->
                MonitoringStartupResult.Failed(
                    MonitoringStartupFailure.ForegroundStartFailed(outcome.cause)
                )

            is ForegroundStartOutcome.PermissionDenied ->
                MonitoringStartupResult.Failed(
                    attributeSecurityException(outcome.cause)
                )

            ForegroundStartOutcome.Started ->
                MonitoringStartupResult.Proceed
        }
    }

    private fun attributeSecurityException(cause: SecurityException): MonitoringStartupFailure = when {
        !hasRecordAudioPermission() -> MonitoringStartupFailure.MicrophonePermissionDenied
        locationPermissionLevel() == LocationPermissionLevel.NONE -> MonitoringStartupFailure.LocationPermissionDenied
        else -> MonitoringStartupFailure.ForegroundStartFailed(cause)
    }
}
