package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDecisionEvaluatorTest {

    @Test
    fun `granted is always GRANTED regardless of rationale or history`() {
        assertEquals(
            SinglePermissionDecision.GRANTED,
            PermissionDecisionEvaluator.evaluate(granted = true, shouldShowRationale = true, requestedBefore = true)
        )
        assertEquals(
            SinglePermissionDecision.GRANTED,
            PermissionDecisionEvaluator.evaluate(granted = true, shouldShowRationale = false, requestedBefore = false)
        )
    }

    @Test
    fun `not granted with rationale shown is an ordinary DENIED, never PERMANENTLY_DENIED`() {
        assertEquals(
            SinglePermissionDecision.DENIED,
            PermissionDecisionEvaluator.evaluate(granted = false, shouldShowRationale = true, requestedBefore = true)
        )
    }

    @Test
    fun `never requested before, no rationale, not granted is DENIED not PERMANENTLY_DENIED`() {
        // This is the exact case the Copilot-adjacent finding warns about: shouldShowRationale
        // being false must not by itself mean "permanently denied" — it's also false before the
        // very first request.
        assertEquals(
            SinglePermissionDecision.DENIED,
            PermissionDecisionEvaluator.evaluate(granted = false, shouldShowRationale = false, requestedBefore = false)
        )
    }

    @Test
    fun `requested before, no rationale, not granted is PERMANENTLY_DENIED`() {
        assertEquals(
            SinglePermissionDecision.PERMANENTLY_DENIED,
            PermissionDecisionEvaluator.evaluate(granted = false, shouldShowRationale = false, requestedBefore = true)
        )
    }
}

class MonitoringPermissionEvaluatorTest {

    private fun granted() = SinglePermissionDecision.GRANTED
    private fun denied() = SinglePermissionDecision.DENIED
    private fun permanentlyDenied() = SinglePermissionDecision.PERMANENTLY_DENIED

    @Test
    fun `microphone and fine location granted is Granted`() {
        assertEquals(
            MonitoringPermissionOutcome.Granted,
            MonitoringPermissionEvaluator.evaluate(granted(), granted(), granted())
        )
    }

    @Test
    fun `microphone granted, only coarse location granted is ApproximateLocationOnly`() {
        assertEquals(
            MonitoringPermissionOutcome.ApproximateLocationOnly,
            MonitoringPermissionEvaluator.evaluate(granted(), denied(), granted())
        )
    }

    @Test
    fun `microphone denied ordinarily blocks start but stays retryable as Denied`() {
        assertEquals(
            MonitoringPermissionOutcome.Denied,
            MonitoringPermissionEvaluator.evaluate(denied(), granted(), granted())
        )
    }

    @Test
    fun `microphone permanently denied is PermanentlyDenied even if location is fully granted`() {
        assertEquals(
            MonitoringPermissionOutcome.PermanentlyDenied,
            MonitoringPermissionEvaluator.evaluate(permanentlyDenied(), granted(), granted())
        )
    }

    @Test
    fun `both location permissions ordinarily denied is Denied, not PermanentlyDenied`() {
        assertEquals(
            MonitoringPermissionOutcome.Denied,
            MonitoringPermissionEvaluator.evaluate(granted(), denied(), denied())
        )
    }

    @Test
    fun `both location permissions permanently denied is PermanentlyDenied`() {
        assertEquals(
            MonitoringPermissionOutcome.PermanentlyDenied,
            MonitoringPermissionEvaluator.evaluate(granted(), permanentlyDenied(), permanentlyDenied())
        )
    }

    @Test
    fun `one location permission permanently denied but the other still retryable stays Denied`() {
        assertEquals(
            MonitoringPermissionOutcome.Denied,
            MonitoringPermissionEvaluator.evaluate(granted(), permanentlyDenied(), denied())
        )
    }

    @Test
    fun `microphone permanently denied and location denied is PermanentlyDenied`() {
        assertEquals(
            MonitoringPermissionOutcome.PermanentlyDenied,
            MonitoringPermissionEvaluator.evaluate(permanentlyDenied(), denied(), denied())
        )
    }
}

class MonitoringPermissionRequestPlanTest {

    @Test
    fun `below API 33, the Start request excludes POST_NOTIFICATIONS`() {
        val permissions = MonitoringPermissionRequestPlan.startPermissions(sdkInt = Build.VERSION_CODES.S)

        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `at API 33 and above, the Start request includes POST_NOTIFICATIONS`() {
        val permissions = MonitoringPermissionRequestPlan.startPermissions(sdkInt = Build.VERSION_CODES.TIRAMISU)

        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `the Start request is a single array — one launch covers mic, location and notifications together`() {
        val permissions = MonitoringPermissionRequestPlan.startPermissions(sdkInt = Build.VERSION_CODES.TIRAMISU)

        assertEquals(4, permissions.size)
    }

    @Test
    fun `the precise-location upgrade request asks only for fine and coarse location`() {
        val permissions = MonitoringPermissionRequestPlan.preciseLocationUpgradePermissions()

        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), permissions.toSet())
        assertFalse(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}

class PreciseLocationUpgradeEvaluatorTest {

    @Test
    fun `fine granted is Granted`() {
        assertEquals(
            PreciseLocationUpgradeOutcome.Granted,
            PreciseLocationUpgradeEvaluator.evaluate(SinglePermissionDecision.GRANTED)
        )
    }

    @Test
    fun `fine denied is Denied`() {
        assertEquals(
            PreciseLocationUpgradeOutcome.Denied,
            PreciseLocationUpgradeEvaluator.evaluate(SinglePermissionDecision.DENIED)
        )
    }

    @Test
    fun `fine permanently denied is PermanentlyDenied`() {
        assertEquals(
            PreciseLocationUpgradeOutcome.PermanentlyDenied,
            PreciseLocationUpgradeEvaluator.evaluate(SinglePermissionDecision.PERMANENTLY_DENIED)
        )
    }
}
