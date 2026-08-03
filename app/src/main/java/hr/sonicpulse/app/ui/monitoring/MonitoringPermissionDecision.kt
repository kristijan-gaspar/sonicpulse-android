package hr.sonicpulse.app.ui.monitoring

/** Per-permission result, disambiguating "can ask again" from "must go to system settings". */
enum class SinglePermissionDecision { GRANTED, DENIED, PERMANENTLY_DENIED }

/**
 * Classifies one permission's request result. `shouldShowRationale == false` is ambiguous on its
 * own — it's true both before the permission has ever been requested and after it's been
 * permanently denied — so [requestedBefore] (our own memory, Android exposes no such API) is
 * required to tell those two apart.
 */
object PermissionDecisionEvaluator {
    fun evaluate(
        granted: Boolean,
        shouldShowRationale: Boolean,
        requestedBefore: Boolean
    ): SinglePermissionDecision = when {
        granted -> SinglePermissionDecision.GRANTED
        shouldShowRationale -> SinglePermissionDecision.DENIED
        requestedBefore -> SinglePermissionDecision.PERMANENTLY_DENIED
        else -> SinglePermissionDecision.DENIED
    }
}

/** What the Monitoring screen should do after a permission request for RECORD_AUDIO + location. */
sealed interface MonitoringPermissionOutcome {
    /** Microphone granted, and precise (fine) location granted. */
    data object Granted : MonitoringPermissionOutcome

    /** Microphone granted, only coarse location granted — enough to start monitoring, but every
     * fix will be inaccurate; the already-built [MonitoringPhase.PreciseLocationRequired] state
     * surfaces this once monitoring is running, so no separate blocking dialog is needed here. */
    data object ApproximateLocationOnly : MonitoringPermissionOutcome

    /** Missing something required, but at least one still-missing permission can be asked again. */
    data object Denied : MonitoringPermissionOutcome

    /** Missing something required, and nothing left can be asked again via the system dialog —
     * only the app's own Settings page can grant it now. */
    data object PermanentlyDenied : MonitoringPermissionOutcome
}

/** Combines the 3 individual monitoring-permission decisions into one screen-level outcome. */
object MonitoringPermissionEvaluator {

    fun evaluate(
        microphone: SinglePermissionDecision,
        fineLocation: SinglePermissionDecision,
        coarseLocation: SinglePermissionDecision
    ): MonitoringPermissionOutcome {
        val micGranted = microphone == SinglePermissionDecision.GRANTED
        val fineGranted = fineLocation == SinglePermissionDecision.GRANTED
        val coarseGranted = coarseLocation == SinglePermissionDecision.GRANTED

        return when {
            micGranted && fineGranted -> MonitoringPermissionOutcome.Granted
            micGranted && coarseGranted -> MonitoringPermissionOutcome.ApproximateLocationOnly
            isPermanentlyBlocked(microphone, fineLocation, coarseLocation, micGranted, fineGranted || coarseGranted) ->
                MonitoringPermissionOutcome.PermanentlyDenied
            else -> MonitoringPermissionOutcome.Denied
        }
    }

    private fun isPermanentlyBlocked(
        microphone: SinglePermissionDecision,
        fineLocation: SinglePermissionDecision,
        coarseLocation: SinglePermissionDecision,
        micGranted: Boolean,
        anyLocationGranted: Boolean
    ): Boolean {
        val micBlocking = !micGranted && microphone == SinglePermissionDecision.PERMANENTLY_DENIED
        val locationBlocking = !anyLocationGranted &&
            fineLocation == SinglePermissionDecision.PERMANENTLY_DENIED &&
            coarseLocation == SinglePermissionDecision.PERMANENTLY_DENIED
        return micBlocking || locationBlocking
    }
}
