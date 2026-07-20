package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBufferSizeCalculatorTest {

    @Test
    fun `uses double the minimum buffer size when it is already larger than double the block size`() {
        val result = AudioBufferSizeCalculator.calculate(minBufferBytes = 10_000, blockSize = 1024)

        assertEquals(20_000, result)
    }

    @Test
    fun `uses double the block size in bytes when the minimum buffer is small`() {
        val result = AudioBufferSizeCalculator.calculate(minBufferBytes = 100, blockSize = 1024)

        // 1024 samples * 2 bytes/sample (Short.SIZE_BYTES) * 2 = 4096
        assertEquals(4_096, result)
    }

    @Test
    fun `picks the larger of the two candidates when they are equal`() {
        // minBufferBytes * 2 == blockSize * 2 (Short.SIZE_BYTES) * 2 when minBufferBytes == blockSize * 2
        val result = AudioBufferSizeCalculator.calculate(minBufferBytes = 2_048, blockSize = 1024)

        assertEquals(4_096, result)
    }
}
