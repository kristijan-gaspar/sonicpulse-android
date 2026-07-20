package hr.sonicpulse.app.data.audio

class AudioBlockAccumulator(private val blockSize: Int) {

    init {
        require(blockSize > 0) { "blockSize must be positive, was $blockSize." }
    }

    private val buffer = ShortArray(blockSize)
    private var filledCount = 0

    fun accumulate(samples: ShortArray, sampleCount: Int, onBlock: (ShortArray) -> Unit) {
        var consumed = 0
        while (consumed < sampleCount) {
            val copyCount = minOf(blockSize - filledCount, sampleCount - consumed)
            System.arraycopy(samples, consumed, buffer, filledCount, copyCount)
            filledCount += copyCount
            consumed += copyCount

            if (filledCount == blockSize) {
                onBlock(buffer.copyOf())
                filledCount = 0
            }
        }
    }
}
