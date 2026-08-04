package hr.sonicpulse.app.ui.permissions

import org.junit.Assert.assertEquals
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
        // shouldShowRationale being false must not by itself mean "permanently denied" — it's
        // also false before the very first request.
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
