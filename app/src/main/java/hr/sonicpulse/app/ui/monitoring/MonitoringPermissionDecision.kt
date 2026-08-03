package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.os.Build

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

/**
 * The exact permission array to request for the Start action — one deterministic request, never
 * two separate launches. [sdkInt] is a parameter (not read from [Build.VERSION.SDK_INT] directly)
 * so this is plain-JVM testable without Robolectric.
 */
object MonitoringPermissionRequestPlan {
    fun startPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** The precise-location upgrade never touches microphone or notifications — only asking
     * again for what's actually missing (fine), paired with coarse per Android's combined dialog. */
    fun preciseLocationUpgradePermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
}

/** Outcome of asking specifically for [Manifest.permission.ACCESS_FINE_LOCATION] while monitoring
 * is already running on coarse-only access — distinct from [MonitoringPermissionOutcome], which
 * governs whether the service may *start* at all (where coarse-only is already sufficient). */
sealed interface PreciseLocationUpgradeOutcome {
    data object Granted : PreciseLocationUpgradeOutcome
    data object Denied : PreciseLocationUpgradeOutcome
    data object PermanentlyDenied : PreciseLocationUpgradeOutcome
}

/** Success requires FINE specifically — a granted/re-granted COARSE alongside it is never enough. */
object PreciseLocationUpgradeEvaluator {
    fun evaluate(fineLocation: SinglePermissionDecision): PreciseLocationUpgradeOutcome = when (fineLocation) {
        SinglePermissionDecision.GRANTED -> PreciseLocationUpgradeOutcome.Granted
        SinglePermissionDecision.DENIED -> PreciseLocationUpgradeOutcome.Denied
        SinglePermissionDecision.PERMANENTLY_DENIED -> PreciseLocationUpgradeOutcome.PermanentlyDenied
    }
}

/**
 * Decides whether returning to the app (ON_RESUME) after opening system Settings for the
 * precise-location upgrade should send exactly one refresh-location intent. Pure and
 * plain-JVM-testable so the Composable's DisposableEffect only has to call it, never branch on
 * these conditions itself. An ordinary resume (the flag false — e.g. switching apps, or Settings
 * opened for an unrelated reason) must never send a refresh.
 */
fun shouldSendPreciseLocationRefreshOnResume(
    openedSettingsForPreciseLocation: Boolean,
    monitoringActive: Boolean,
    fineLocationGranted: Boolean
): Boolean = openedSettingsForPreciseLocation && monitoringActive && fineLocationGranted
