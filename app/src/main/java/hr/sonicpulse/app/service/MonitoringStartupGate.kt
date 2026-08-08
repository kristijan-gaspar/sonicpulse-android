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
    /** The OS rejected the promotion with a SecurityException — could be RECORD_AUDIO or, for the
     * microphone+location promotion only, location; the gate attributes which one by re-checking
     * only what that specific promotion depends on, preserving [cause]. */
    data class PermissionDenied(val cause: SecurityException) : ForegroundStartOutcome
    /** The OS rejected the promotion for a reason unrelated to permissions (e.g. app state). */
    data class Failed(val cause: Throwable) : ForegroundStartOutcome
}

/**
 * Decides whether it's safe to start audio capture and location updates. Foreground promotion
 * happens in two stages, both gated behind this function: a microphone-only [startForeground]
 * runs immediately after the RECORD_AUDIO check — before either location check — so the service
 * always promotes to the foreground promptly no matter how long the location checks take (Android
 * requires `startForeground()` shortly after `startForegroundService()`, on pain of the system
 * killing the service). Only once location permission and services are both confirmed does
 * [enableLocationForegroundType] add the location foreground service type. Checks run in order:
 * RECORD_AUDIO permission, microphone-only foreground promotion, RECORD_AUDIO again (Android 14+
 * re-validates permissions at promotion time, so it can fail even when the check moments earlier
 * passed), location permission (COARSE or FINE), system location services enabled, then the
 * location foreground promotion. There is no equivalent re-validation of location services at
 * promotion time.
 *
 * A SecurityException from either promotion is attributed only to the permission(s) that specific
 * promotion actually depends on — never both indiscriminately. The microphone-only promotion only
 * ever requests FOREGROUND_SERVICE_TYPE_MICROPHONE, so its SecurityException can only be a
 * RECORD_AUDIO problem; checking location permission for it would misattribute the failure. The
 * microphone+location promotion additionally requests FOREGROUND_SERVICE_TYPE_LOCATION, so its
 * SecurityException is checked against both. If neither explains it, the original
 * SecurityException is preserved as an unattributed [MonitoringStartupFailure.ForegroundStartFailed],
 * never assumed to be a permission problem by default. [ForegroundStartOutcome.Failed] (e.g.
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
                    attributeMicrophonePromotionFailure(outcome.cause)
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
                    attributeLocationPromotionFailure(outcome.cause)
                )

            ForegroundStartOutcome.Started ->
                MonitoringStartupResult.Proceed
        }
    }

    /** The microphone-only promotion only ever requests FOREGROUND_SERVICE_TYPE_MICROPHONE, so a
     * SecurityException from it can only be a RECORD_AUDIO problem — location permission is
     * irrelevant to this specific call and must never be consulted here. */
    private fun attributeMicrophonePromotionFailure(cause: SecurityException): MonitoringStartupFailure =
        if (!hasRecordAudioPermission()) {
            MonitoringStartupFailure.MicrophonePermissionDenied
        } else {
            MonitoringStartupFailure.ForegroundStartFailed(cause)
        }

    /** The microphone+location promotion requests both types, so a SecurityException from it can
     * come from either permission. */
    private fun attributeLocationPromotionFailure(cause: SecurityException): MonitoringStartupFailure = when {
        !hasRecordAudioPermission() -> MonitoringStartupFailure.MicrophonePermissionDenied
        locationPermissionLevel() == LocationPermissionLevel.NONE -> MonitoringStartupFailure.LocationPermissionDenied
        else -> MonitoringStartupFailure.ForegroundStartFailed(cause)
    }
}
