package hr.sonicpulse.app.service

/** Nonfatal outcomes of a location-only refresh (see [MonitoringService.refreshLocationIntent]) —
 * unlike [MonitoringStartupFailure], none of these ever stop monitoring or set isMonitoring false. */
sealed interface LocationRefreshFailure {
    data object PermissionDenied : LocationRefreshFailure
    data object LocationServicesDisabled : LocationRefreshFailure
    data object Failed : LocationRefreshFailure
}
