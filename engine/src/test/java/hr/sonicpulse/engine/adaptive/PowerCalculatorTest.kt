package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
