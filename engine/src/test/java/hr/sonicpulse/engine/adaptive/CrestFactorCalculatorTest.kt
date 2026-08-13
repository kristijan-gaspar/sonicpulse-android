package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CrestFactorCalculatorTest {

    @Test
    fun `returns null for silence`() {
        val samples = ShortArray(1024)

        assertNull(CrestFactorCalculator.calculate(samples))
    }

    @Test
    fun `returns zero dB for a uniform-amplitude hop`() {
        val samples = ShortArray(1024) { 5000 }

        assertEquals(0.0, CrestFactorCalculator.calculate(samples)!!, 1e-9)
    }

    @Test
    fun `returns a positive crest factor for a single spike among near-silent samples`() {
        val samples = ShortArray(1024) { 5 }
        samples[0] = 500

        val crest = CrestFactorCalculator.calculate(samples)!!

        assertEquals(true, crest > 0.0)
    }

    @Test
    fun `throws for empty samples`() {
        assertThrows(IllegalArgumentException::class.java) {
            CrestFactorCalculator.calculate(shortArrayOf())
        }
    }

    @Test
    fun `sample window overload returns zero dB for uniform samples`() {
        val samples = ShortArray(4096) { 5000 }

        val window = object : SampleWindow {
            override val size = samples.size
            override fun get(index: Int): Short = samples[index]
        }

        assertEquals(0.0, CrestFactorCalculator.calculate(window)!!, 1e-9)
    }

    @Test
    fun `sample window overload matches short array calculation`() {
        val samples = ShortArray(4096) { 5 }
        samples[123] = 500

        val window = object : SampleWindow {
            override val size = samples.size
            override fun get(index: Int): Short = samples[index]
        }

        val fromArray = CrestFactorCalculator.calculate(samples)
        val fromWindow = CrestFactorCalculator.calculate(window)

        assertEquals(fromArray!!, fromWindow!!, 1e-12)
    }
}
