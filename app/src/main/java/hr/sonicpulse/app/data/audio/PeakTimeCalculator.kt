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
        // totalSamples * 1_000_000_000L would overflow Long for large peakBlockIndex (e.g.
        // beyond ~9,007,199 at blockSize=1024/sampleRate=44100). Splitting into whole seconds
        // (a plain integer division, no nanosecond scaling) and a sub-second remainder (always
        // < sampleRate samples, so its nanosecond conversion never overflows) avoids that.
        val totalSamples = peakBlockIndex * blockSize
        val wholeSeconds = totalSamples / sampleRate
        val remainderSamples = totalSamples % sampleRate
        val remainderNanos = remainderSamples * 1_000_000_000L / sampleRate
        return firstBlockInstant.plusSeconds(wholeSeconds).plusNanos(remainderNanos)
    }
}
