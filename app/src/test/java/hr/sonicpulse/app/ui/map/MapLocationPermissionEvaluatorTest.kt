package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.ui.permissions.SinglePermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLocationPermissionEvaluatorTest {

    private fun granted() = SinglePermissionDecision.GRANTED
    private fun denied() = SinglePermissionDecision.DENIED
    private fun permanentlyDenied() = SinglePermissionDecision.PERMANENTLY_DENIED

    @Test
    fun `fine granted alone is Granted`() {
        assertEquals(MapLocationPermissionOutcome.Granted, MapLocationPermissionEvaluator.evaluate(granted(), denied()))
    }

    @Test
    fun `coarse granted alone is Granted — approximate location is sufficient`() {
        assertEquals(MapLocationPermissionOutcome.Granted, MapLocationPermissionEvaluator.evaluate(denied(), granted()))
    }

    @Test
    fun `both denied but retryable is Denied`() {
        assertEquals(MapLocationPermissionOutcome.Denied, MapLocationPermissionEvaluator.evaluate(denied(), denied()))
    }

    @Test
    fun `both permanently denied is PermanentlyDenied`() {
        assertEquals(
            MapLocationPermissionOutcome.PermanentlyDenied,
            MapLocationPermissionEvaluator.evaluate(permanentlyDenied(), permanentlyDenied())
        )
    }

    @Test
    fun `one permanently denied but the other still retryable stays Denied`() {
        assertEquals(MapLocationPermissionOutcome.Denied, MapLocationPermissionEvaluator.evaluate(permanentlyDenied(), denied()))
    }
}
