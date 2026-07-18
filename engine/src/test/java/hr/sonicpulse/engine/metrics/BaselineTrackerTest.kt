package hr.sonicpulse.engine.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineTrackerTest {

    private val tracker = BaselineTracker(alphaDown = 0.10, alphaUp = 0.02)

    @Test
    fun `first update seeds baseline to that value, not to a floor`() {
        tracker.update(-45.0)

        assertEquals(-45.0, tracker.value, 0.0)
    }

    @Test
    fun `adapts toward a lower dbfs using alphaDown`() {
        tracker.update(-30.0)

        tracker.update(-40.0)

        assertEquals(-31.0, tracker.value, 1e-9)
    }

    @Test
    fun `adapts toward a higher dbfs using alphaUp, more slowly than alphaDown`() {
        tracker.update(-30.0)

        tracker.update(-10.0)

        assertEquals(-29.6, tracker.value, 1e-9)
    }
}
