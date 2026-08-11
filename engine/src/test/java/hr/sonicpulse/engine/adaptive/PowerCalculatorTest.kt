package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerCalculatorTest {

    @Test
    fun `calculate returns zero for silence`() {
        val samples = ShortArray(1024)

        val result = PowerCalculator.calculate(samples)

        assertEquals(0.0, result, 0.0)
    }

    @Test
    fun `calculate returns one for full-scale samples`() {
        val samples = ShortArray(1024) { Short.MIN_VALUE }

        val result = PowerCalculator.calculate(samples)

        assertEquals(1.0, result, 1e-9)
    }

    @Test
    fun `calculate returns normalized mean square for half-scale samples`() {
        val samples = ShortArray(1024) { 16_384 }

        val result = PowerCalculator.calculate(samples)

        assertEquals(0.25, result, 1e-9)
    }

    @Test
    fun `calculate averages power across samples of differing amplitude`() {
        val samples = shortArrayOf(0, Short.MIN_VALUE, 0, Short.MIN_VALUE)

        val result = PowerCalculator.calculate(samples)

        assertEquals(0.5, result, 1e-9)
    }

    @Test
    fun `calculate treats negative and positive samples of equal magnitude the same`() {
        val positive = ShortArray(1024) { 16_384 }
        val negative = ShortArray(1024) { -16_384 }

        assertEquals(PowerCalculator.calculate(positive), PowerCalculator.calculate(negative), 0.0)
    }

    @Test
    fun `calculate throws for empty samples`() {
        assertThrows(IllegalArgumentException::class.java) {
            PowerCalculator.calculate(shortArrayOf())
        }
    }

    @Test
    fun `calculate returns normalized mean square for Short_MAX_VALUE samples`() {
        val samples = ShortArray(1024) { Short.MAX_VALUE }
        val expected = (Short.MAX_VALUE / 32_768.0).let { it * it }

        val result = PowerCalculator.calculate(samples)

        assertEquals(expected, result, 1e-12)
    }

    @Test
    fun `calculate result is always finite and non-negative`() {
        val samples = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 12_345, -6_789, 1, -1)

        val result = PowerCalculator.calculate(samples)

        assertTrue(result.isFinite())
        assertTrue(result >= 0.0)
    }

    @Test
    fun `calculate over a SampleWindow matches calculate over the equivalent ShortArray`() {
        val config = AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 4096)
        val window = RollingAnalysisWindow(config)
        window.update(ShortArray(1024) { 100 })
        window.update(ShortArray(1024) { 200 })
        window.update(ShortArray(1024) { 300 })
        val sampleWindow = window.update(ShortArray(1024) { 400 })!!

        val equivalentArray = ShortArray(4096).also {
            ShortArray(1024) { 100 }.copyInto(it, 0)
            ShortArray(1024) { 200 }.copyInto(it, 1024)
            ShortArray(1024) { 300 }.copyInto(it, 2048)
            ShortArray(1024) { 400 }.copyInto(it, 3072)
        }

        assertEquals(PowerCalculator.calculate(equivalentArray), PowerCalculator.calculate(sampleWindow), 1e-12)
    }

    @Test
    fun `calculate over a SampleWindow is correct after the ring buffer wraps around`() {
        val config = AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 4096)
        val window = RollingAnalysisWindow(config)

        val hopValues = shortArrayOf(100, 200, 300, 400, 500, 600, 700)
        var sampleWindow: SampleWindow? = null
        for (value in hopValues) {
            sampleWindow = window.update(ShortArray(1024) { value })
        }
        // Capacity is 4 hops, so after 7 hops the physical write position has
        // already wrapped once. The logical window must still be the last 4
        // hop values in order: 400, 500, 600, 700.

        val retainedHopValues = shortArrayOf(400, 500, 600, 700)
        val expected = retainedHopValues.sumOf { value -> (value / 32_768.0).let { it * it } } /
            retainedHopValues.size

        val result = PowerCalculator.calculate(sampleWindow!!)

        assertEquals(expected, result, 1e-12)
    }
}
