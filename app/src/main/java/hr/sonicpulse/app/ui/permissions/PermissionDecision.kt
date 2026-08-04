package hr.sonicpulse.app.ui.permissions

/** Per-permission result, disambiguating "can ask again" from "must go to system settings".
 * Generic — used by any screen requesting a runtime permission, not just Monitoring. */
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
