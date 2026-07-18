package hr.sonicpulse.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelCalculatorTest {

    @Test
    fun `calculate returns negative infinity for silence`() {
        val samples = ShortArray(1024)

        val result = AudioLevelCalculator.calculate(samples)

        assertEquals(0.0, result.rms, 0.0)
        assertTrue(result.dbfs == Double.NEGATIVE_INFINITY)
    }

    @Test
    fun `calculate returns approximately minus six dbfs for half scale signal`() {
        val samples = ShortArray(1024) { 16_384 }

        val result = AudioLevelCalculator.calculate(samples)

        assertEquals(16_384.0, result.rms, 0.0)
        assertEquals(-6.0206, result.dbfs, 0.0001)
    }

    @Test
    fun `calculate returns zero dbfs for full scale signal`() {
        val samples = ShortArray(1024) { Short.MIN_VALUE }

        val result = AudioLevelCalculator.calculate(samples)

        assertEquals(32_768.0, result.rms, 0.0)
        assertEquals(0.0, result.dbfs, 0.0)
    }

    @Test
    fun `calculate throws exception for empty samples`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioLevelCalculator.calculate(shortArrayOf())
        }
    }
}