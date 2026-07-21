package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
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

    @Test
    fun `does not overflow for a peak block index beyond the naive-formula's Long overflow threshold`() {
        // For blockSize = 1024, peakBlockIndex * blockSize * 1_000_000_000L overflows Long
        // (~9.22e18) once peakBlockIndex exceeds ~9,007,199 — this index is just past that.
        val firstBlockInstant = Instant.parse("2026-01-01T00:00:00Z")
        val peakBlockIndex = 9_007_200L

        val result = PeakTimeCalculator.calculate(firstBlockInstant, peakBlockIndex, sampleRate, blockSize)

        // Expected value computed independently via BigInteger (no overflow risk at all),
        // to avoid the test's own arithmetic reproducing the implementation's overflow bug.
        val totalSamples = BigInteger.valueOf(peakBlockIndex) * BigInteger.valueOf(blockSize.toLong())
        val totalNanos = totalSamples * BigInteger.valueOf(1_000_000_000L) / BigInteger.valueOf(sampleRate.toLong())
        val billion = BigInteger.valueOf(1_000_000_000L)
        val expectedSeconds = (totalNanos / billion).toLong()
        val expectedNanoRemainder = (totalNanos % billion).toLong()

        assertEquals(firstBlockInstant.plusSeconds(expectedSeconds).plusNanos(expectedNanoRemainder), result)
    }
}
