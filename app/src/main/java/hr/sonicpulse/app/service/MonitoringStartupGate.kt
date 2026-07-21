package hr.sonicpulse.app.service

sealed interface MonitoringStartupResult {
    data object Proceed : MonitoringStartupResult
    data object PermissionDenied : MonitoringStartupResult
}

/**
 * Decides whether it's safe to start audio capture. RECORD_AUDIO is checked twice: once before
 * attempting to promote the service to the foreground, and once after — Android 14+ validates
 * the permission again when promoting a microphone-typed foreground service, so [startForeground]
 * itself may fail (a caught SecurityException, reported here as `false`) even when the first
 * check passed, and the permission could in principle be revoked in between the two checks.
 */
class MonitoringStartupGate(
    private val hasRecordAudioPermission: () -> Boolean,
    private val startForeground: () -> Boolean
) {
    fun attemptStartup(): MonitoringStartupResult {
        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.PermissionDenied
        }
        if (!startForeground()) {
            return MonitoringStartupResult.PermissionDenied
        }
        if (!hasRecordAudioPermission()) {
            return MonitoringStartupResult.PermissionDenied
        }
        return MonitoringStartupResult.Proceed
    }
}
