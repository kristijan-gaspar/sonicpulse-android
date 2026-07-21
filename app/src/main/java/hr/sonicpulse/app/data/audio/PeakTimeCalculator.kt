package hr.sonicpulse.app.data.audio

import java.time.Instant

/**
 * Converts a DetectionEvent's block-index peak (per algorithm doc §2.7) into a wall-clock
 * instant. Must be derived from firstBlockInstant and peakBlockIndex — never `Instant.now()`
 * at emission time, since the engine emits after the DETECTING window's end-silence has passed.
 */
object PeakTimeCalculator {

    fun calculate(
        firstBlockInstant: Instant,
        peakBlockIndex: Long,
        sampleRate: Int,
        blockSize: Int
    ): Instant {
        val nanos = peakBlockIndex * blockSize * 1_000_000_000L / sampleRate
        return firstBlockInstant.plusNanos(nanos)
    }
}
