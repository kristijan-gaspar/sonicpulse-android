package hr.sonicpulse.app.data.location

sealed interface LocationStartResult {
    data object Started : LocationStartResult
    data object PermissionDenied : LocationStartResult
    data object LocationServicesDisabled : LocationStartResult
    data object Cancelled : LocationStartResult
    data class Failed(val cause: Throwable) : LocationStartResult
}
