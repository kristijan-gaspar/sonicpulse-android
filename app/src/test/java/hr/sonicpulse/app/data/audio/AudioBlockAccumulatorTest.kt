package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioBlockAccumulatorTest {

    @Test
    fun `a read that exactly fills one block emits exactly one block`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4), sampleCount = 4) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `a read smaller than the block size emits nothing yet`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2), sampleCount = 2) { emitted += it }

        assertEquals(0, emitted.size)
    }

    @Test
    fun `a partial read is carried over and completed by a later read`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2), sampleCount = 2) { emitted += it }
        accumulator.accumulate(shortArrayOf(3, 4), sampleCount = 2) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `a single read spanning a block boundary emits the completed block and carries the remainder`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4, 5, 6), sampleCount = 6) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())

        // The remaining 2 samples (5, 6) must have carried over into the accumulator's state.
        accumulator.accumulate(shortArrayOf(7, 8), sampleCount = 2) { emitted += it }
        assertEquals(2, emitted.size)
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), emitted[1])
    }

    @Test
    fun `a single read spanning multiple full blocks emits them in order`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), sampleCount = 8) { emitted += it }

        assertEquals(2, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), emitted[1])
    }

    @Test
    fun `only sampleCount samples are consumed, ignoring stale data beyond it in the array`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        // AudioRecord.read() may return fewer samples than the array's capacity; anything
        // beyond sampleCount is leftover from a previous read and must be ignored.
        accumulator.accumulate(shortArrayOf(1, 2, 99, 99), sampleCount = 2) { emitted += it }
        accumulator.accumulate(shortArrayOf(3, 4, 99, 99), sampleCount = 2) { emitted += it }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `emitted blocks are independent copies, not aliases of internal state`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4), sampleCount = 4) { emitted += it }
        accumulator.accumulate(shortArrayOf(5, 6, 7, 8), sampleCount = 4) { emitted += it }

        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), emitted[1])
    }

    @Test
    fun `rejects a non-positive block size`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioBlockAccumulator(blockSize = 0)
        }
    }
}
