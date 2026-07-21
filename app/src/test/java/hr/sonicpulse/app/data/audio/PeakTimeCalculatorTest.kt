package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PeakTimeCalculatorTest {

    private val sampleRate = 44_100
    private val blockSize = 1_024

    @Test
    fun `peak block index zero returns the first block instant unchanged`() {
        val firstBlockInstant = Instant.parse("2026-01-01T00:00:00Z")

        val result = PeakTimeCalculator.calculate(firstBlockInstant, peakBlockIndex = 0, sampleRate, blockSize)

        assertEquals(firstBlockInstant, result)
    }

    @Test
    fun `peak block index one adds exactly one block duration`() {
        val firstBlockInstant = Instant.parse("2026-01-01T00:00:00Z")
        val expectedNanos = blockSize * 1_000_000_000L / sampleRate

        val result = PeakTimeCalculator.calculate(firstBlockInstant, peakBlockIndex = 1, sampleRate, blockSize)

        assertEquals(firstBlockInstant.plusNanos(expectedNanos), result)
    }

    @Test
    fun `peak block index ten adds ten block durations`() {
        val firstBlockInstant = Instant.parse("2026-01-01T00:00:00Z")
        val expectedNanos = 10L * blockSize * 1_000_000_000L / sampleRate

        val result = PeakTimeCalculator.calculate(firstBlockInstant, peakBlockIndex = 10, sampleRate, blockSize)

        assertEquals(firstBlockInstant.plusNanos(expectedNanos), result)
    }
}
