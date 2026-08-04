package hr.sonicpulse.app.ui.map

import android.Manifest
import hr.sonicpulse.app.ui.permissions.SinglePermissionDecision

/** What the current-location button should do after its permission request resolves. Unlike
 * Monitoring's [hr.sonicpulse.app.ui.monitoring.MonitoringPermissionEvaluator], approximate
 * (coarse-only) location is sufficient on its own — the puck and accuracy circle work fine with
 * either grant, so this is simply "is at least one of fine/coarse granted". */
sealed interface MapLocationPermissionOutcome {
    data object Granted : MapLocationPermissionOutcome
    data object Denied : MapLocationPermissionOutcome
    data object PermanentlyDenied : MapLocationPermissionOutcome
}

object MapLocationPermissionEvaluator {
    fun evaluate(
        fineLocation: SinglePermissionDecision,
        coarseLocation: SinglePermissionDecision
    ): MapLocationPermissionOutcome = when {
        fineLocation == SinglePermissionDecision.GRANTED || coarseLocation == SinglePermissionDecision.GRANTED ->
            MapLocationPermissionOutcome.Granted
        fineLocation == SinglePermissionDecision.PERMANENTLY_DENIED &&
            coarseLocation == SinglePermissionDecision.PERMANENTLY_DENIED -> MapLocationPermissionOutcome.PermanentlyDenied
        else -> MapLocationPermissionOutcome.Denied
    }
}

/** Both permissions are requested together, in one launch — never fine and coarse separately. */
fun mapLocationPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)
