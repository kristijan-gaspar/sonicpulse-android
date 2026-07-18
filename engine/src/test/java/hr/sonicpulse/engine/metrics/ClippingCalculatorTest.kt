package hr.sonicpulse.engine.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class ClippingCalculatorTest {

    @Test
    fun `returns zero for a block with no samples at or above clip level`() {
        val samples = ShortArray(1024) { 100 }

        val ratio = ClippingCalculator.calculateClipRatio(samples, clipLevel = 32_000)

        assertEquals(0.0, ratio, 0.0)
    }

    @Test
    fun `counts samples at or above clip level, both positive and negative`() {
        val samples = ShortArray(1024) { 0 }
        samples[0] = 32_000
        samples[1] = -32_000
        samples[2] = 32_767

        val ratio = ClippingCalculator.calculateClipRatio(samples, clipLevel = 32_000)

        assertEquals(3.0 / 1024.0, ratio, 1e-9)
    }

    @Test
    fun `sample exactly one below clip level does not count`() {
        val samples = ShortArray(1024) { 0 }
        samples[0] = 31_999

        val ratio = ClippingCalculator.calculateClipRatio(samples, clipLevel = 32_000)

        assertEquals(0.0, ratio, 0.0)
    }

    @Test
    fun `all samples clipped returns ratio of one`() {
        val samples = ShortArray(1024) { Short.MIN_VALUE }

        val ratio = ClippingCalculator.calculateClipRatio(samples, clipLevel = 32_000)

        assertEquals(1.0, ratio, 0.0)
    }
}