package hr.sonicpulse.app.data.audio

object AudioBufferSizeCalculator {

    fun calculate(minBufferBytes: Int, blockSize: Int): Int =
        maxOf(minBufferBytes * 2, blockSize * Short.SIZE_BYTES * 2)
}