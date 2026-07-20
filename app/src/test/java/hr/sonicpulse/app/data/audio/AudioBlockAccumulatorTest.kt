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

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4), sampleCount = 4) { emitted += it.copyOf() }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `a read smaller than the block size emits nothing yet`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2), sampleCount = 2) { emitted += it.copyOf() }

        assertEquals(0, emitted.size)
    }

    @Test
    fun `a partial read is carried over and completed by a later read`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2), sampleCount = 2) { emitted += it.copyOf() }
        accumulator.accumulate(shortArrayOf(3, 4), sampleCount = 2) { emitted += it.copyOf() }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `a single read spanning a block boundary emits the completed block and carries the remainder`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4, 5, 6), sampleCount = 6) { emitted += it.copyOf() }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())

        // The remaining 2 samples (5, 6) must have carried over into the accumulator's state.
        accumulator.accumulate(shortArrayOf(7, 8), sampleCount = 2) { emitted += it.copyOf() }
        assertEquals(2, emitted.size)
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), emitted[1])
    }

    @Test
    fun `a single read spanning multiple full blocks emits them in order`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), sampleCount = 8) { emitted += it.copyOf() }

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
        accumulator.accumulate(shortArrayOf(1, 2, 99, 99), sampleCount = 2) { emitted += it.copyOf() }
        accumulator.accumulate(shortArrayOf(3, 4, 99, 99), sampleCount = 2) { emitted += it.copyOf() }

        assertEquals(1, emitted.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted.single())
    }

    @Test
    fun `synchronous callback contents are correct across consecutive blocks in a single read`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val observed = mutableListOf<ShortArray>()

        // The accumulator reuses its internal buffer (no per-block allocation); a real
        // consumer that needs to retain a block beyond the callback must copy it here,
        // exactly like this test does. This proves each callback sees correct contents
        // at the moment it fires, even though the buffer is overwritten afterward.
        accumulator.accumulate(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), sampleCount = 8) { block ->
            observed += block.copyOf()
        }

        assertEquals(2, observed.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), observed[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), observed[1])
    }

    @Test
    fun `zero sampleCount is a safe no-op`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)
        val emitted = mutableListOf<ShortArray>()

        accumulator.accumulate(shortArrayOf(1, 2, 3, 4), sampleCount = 0) { emitted += it.copyOf() }

        assertEquals(0, emitted.size)
    }

    @Test
    fun `rejects a negative sampleCount`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.accumulate(shortArrayOf(1, 2, 3, 4), sampleCount = -1) { }
        }
    }

    @Test
    fun `rejects a sampleCount greater than the array size`() {
        val accumulator = AudioBlockAccumulator(blockSize = 4)

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.accumulate(shortArrayOf(1, 2), sampleCount = 3) { }
        }
    }

    @Test
    fun `rejects a non-positive block size`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioBlockAccumulator(blockSize = 0)
        }
    }
}
