package hr.sonicpulse.engine.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeCalculatorTest {

    @Test
    fun `spike is dbfs minus baseline when dbfs is above baseline`() {
        val spike = SpikeCalculator.calculateSpike(dbfs = -5.0, baseline = -20.0)

        assertEquals(15.0, spike, 0.0)
    }

    @Test
    fun `spike is negative when dbfs is below baseline`() {
        val spike = SpikeCalculator.calculateSpike(dbfs = -50.0, baseline = -20.0)

        assertEquals(-30.0, spike, 0.0)
    }
}