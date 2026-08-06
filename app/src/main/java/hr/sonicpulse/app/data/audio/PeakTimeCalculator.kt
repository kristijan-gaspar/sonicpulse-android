package hr.sonicpulse.app.data.audio

import java.time.Instant

/**
 * Converts a DetectionEvent's block-index peak (per algorithm doc §2.7) into a wall-clock
 * instant. Must be derived from firstBlockInstant and peakBlockIndex — never `Instant.now()`
 * at emission time, since the engine emits after the DETECTING window's end-silence has passed.
 */
object PeakTimeCalculator {

    /**
     * Anchors block zero's start instant from the wall-clock instant the *first completed block*
     * actually arrived — which is already one block duration after capture genuinely began, since
     * the caller cannot observe a block until [blockSize] samples have been read. Subtracting that
     * one block's duration corrects for that systematic offset (~23ms at the default 1024/44100
     * configuration) so [calculate] measures every later peak from the true start of capture,
     * not from one block-duration late.
     */
    fun blockZeroInstant(firstBlockArrivalInstant: Instant, sampleRate: Int, blockSize: Int): Instant {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate." }
        require(blockSize > 0) { "blockSize must be positive, was $blockSize." }

        val blockDurationNanos = blockSize.toLong() * 1_000_000_000L / sampleRate
        return firstBlockArrivalInstant.minusNanos(blockDurationNanos)
    }

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
