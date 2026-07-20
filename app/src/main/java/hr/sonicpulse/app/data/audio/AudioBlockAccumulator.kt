package hr.sonicpulse.app.data.audio

/**
 * Accumulates partial reads into fixed-size blocks (algorithm doc §0: `AudioRecord.read()`
 * may return fewer samples than requested).
 *
 * Ownership contract: the [ShortArray] passed to `onBlock` is a reusable internal buffer,
 * valid only for the duration of that callback invocation — it is overwritten on the next
 * completed block. A caller that needs to retain the data after the callback returns must
 * copy it (e.g. `block.copyOf()`) before returning.
 */
class AudioBlockAccumulator(private val blockSize: Int) {

    init {
        require(blockSize > 0) { "blockSize must be positive, was $blockSize." }
    }

    private val buffer = ShortArray(blockSize)
    private var filledCount = 0

    fun accumulate(samples: ShortArray, sampleCount: Int, onBlock: (ShortArray) -> Unit) {
        require(sampleCount in 0..samples.size) {
            "sampleCount must be within 0..${samples.size}, was $sampleCount."
        }

        var consumed = 0
        while (consumed < sampleCount) {
            val copyCount = minOf(blockSize - filledCount, sampleCount - consumed)
            System.arraycopy(samples, consumed, buffer, filledCount, copyCount)
            filledCount += copyCount
            consumed += copyCount

            if (filledCount == blockSize) {
                onBlock(buffer)
                filledCount = 0
            }
        }
    }
}
