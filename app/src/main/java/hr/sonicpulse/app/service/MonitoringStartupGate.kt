package hr.sonicpulse.app.service

sealed interface MonitoringStartupResult {
    data object Proceed : MonitoringStartupResult
    data object PermissionDenied : MonitoringStartupResult
    data class ForegroundStartFailed(val cause: Throwable) : MonitoringStartupResult
}

/** Outcome of attempting to promote the service to the foreground. */
sealed interface ForegroundStartOutcome {
    data object Started : ForegroundStartOutcome
    /** The OS rejected the promotion specifically due to a missing/denied RECORD_AUDIO permission. */
    data object PermissionDenied : ForegroundStartOutcome
    /** The OS rejected the promotion for a reason unrelated to RECORD_AUDIO (e.g. app state). */
    data class Failed(val cause: Throwable) : ForegroundStartOutcome
}

/**
 * Decides whether it's safe to start audio capture. RECORD_AUDIO is checked twice: once before
 * attempting to promote the service to the foreground, and once after — Android 14+ validates
 * the permission again when promoting a microphone-typed foreground service, so [startForeground]
 * itself may fail with [ForegroundStartOutcome.PermissionDenied] even when the first check passed.
 * A [ForegroundStartOutcome.Failed] outcome (e.g. ForegroundServiceStartNotAllowedException) is a
 * distinct, non-permission failure and must not be conflated with a denied RECORD_AUDIO permission.
 */
class MonitoringStartupGate(
    private val hasRecordAudioPermission: () -> Boolean,
    private val startForeground: () -> ForegroundStartOutcome
) {
    fun attemptStartup(): MonitoringStartupResult {
        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.PermissionDenied
        }
        return when (val outcome = startForeground()) {
            ForegroundStartOutcome.PermissionDenied -> MonitoringStartupResult.PermissionDenied
            is ForegroundStartOutcome.Failed -> MonitoringStartupResult.ForegroundStartFailed(outcome.cause)
            ForegroundStartOutcome.Started -> {
                if (!hasRecordAudioPermission()) {
                    MonitoringStartupResult.PermissionDenied
                } else {
                    MonitoringStartupResult.Proceed
                }
            }
        }
    }
}
