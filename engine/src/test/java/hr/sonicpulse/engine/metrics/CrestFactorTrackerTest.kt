package hr.sonicpulse.engine.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

class CrestFactorTrackerTest {

    private val tracker = CrestFactorTracker(windowBlocks = 3)

    @Test
    fun `single constant block has zero crest`() {
        tracker.addBlock(shortArrayOf(1000, 1000, 1000, 1000))

        assertEquals(0.0, tracker.currentCrest()!!, 1e-9)
    }

    @Test
    fun `combines peak and rms across all blocks currently in the window`() {
        tracker.addBlock(shortArrayOf(0, 0, 0, 0))
        tracker.addBlock(shortArrayOf(4000, 0, 0, 0))
        tracker.addBlock(shortArrayOf(0, 0, 0, 0))

        val expected = 10.0 * log10(12.0)
        assertEquals(expected, tracker.currentCrest()!!, 1e-9)
    }

    @Test
    fun `window only keeps the most recent blocks, older blocks are evicted`() {
        tracker.addBlock(shortArrayOf(20000, 20000, 20000, 20000))
        tracker.addBlock(shortArrayOf(1000, 1000, 1000, 1000))
        tracker.addBlock(shortArrayOf(1000, 1000, 1000, 1000))
        tracker.addBlock(shortArrayOf(1000, 1000, 1000, 1000))

        assertEquals(0.0, tracker.currentCrest()!!, 1e-9)
    }

    @Test
    fun `returns null when the window has no signal at all`() {
        tracker.addBlock(shortArrayOf(0, 0, 0, 0))

        assertNull(tracker.currentCrest())
    }
}
