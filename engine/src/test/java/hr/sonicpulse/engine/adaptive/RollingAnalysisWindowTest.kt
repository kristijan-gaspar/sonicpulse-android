package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RollingAnalysisWindowTest {

    private val config = AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 4096)
    private lateinit var window: RollingAnalysisWindow

    @Before
    fun setUp() {
        window = RollingAnalysisWindow(config)
    }

    private fun hopOf(value: Short): ShortArray = ShortArray(config.hopSize) { value }

    private fun SampleWindow.toList(): List<Short> = (0 until size).map { this[it] }

    private fun expectedWindow(vararg hopValues: Short): List<Short> =
        hopValues.flatMap { value -> List(config.hopSize) { value } }

    @Test
    fun `update returns null while fewer than analysisWindowSize samples have accumulated`() {
        assertNull(window.update(hopOf(1)))
        assertNull(window.update(hopOf(2)))
        assertNull(window.update(hopOf(3)))
    }

    @Test
    fun `first complete window is in correct chronological order`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val result = window.update(hopOf(4))!!

        assertEquals(expectedWindow(1, 2, 3, 4), result.toList())
    }

    @Test
    fun `consecutive rotations replace the oldest hop and keep the rest in order`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        assertEquals(expectedWindow(1, 2, 3, 4), window.update(hopOf(4))!!.toList())
        assertEquals(expectedWindow(2, 3, 4, 5), window.update(hopOf(5))!!.toList())
        assertEquals(expectedWindow(3, 4, 5, 6), window.update(hopOf(6))!!.toList())
        assertEquals(expectedWindow(4, 5, 6, 7), window.update(hopOf(7))!!.toList())
    }

    @Test
    fun `rotations remain correct through a full ring-buffer wrap-around`() {
        // Capacity is 4 hops, so the physical write position completes one full
        // revolution of the ring buffer every 4 hops after the window first fills.
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        window.update(hopOf(4))

        assertEquals(expectedWindow(2, 3, 4, 5), window.update(hopOf(5))!!.toList())
        assertEquals(expectedWindow(3, 4, 5, 6), window.update(hopOf(6))!!.toList())
        assertEquals(expectedWindow(4, 5, 6, 7), window.update(hopOf(7))!!.toList())
        // Physical write position wraps back to the start of the buffer here.
        assertEquals(expectedWindow(5, 6, 7, 8), window.update(hopOf(8))!!.toList())
        assertEquals(expectedWindow(6, 7, 8, 9), window.update(hopOf(9))!!.toList())
        assertEquals(expectedWindow(7, 8, 9, 10), window.update(hopOf(10))!!.toList())
        assertEquals(expectedWindow(8, 9, 10, 11), window.update(hopOf(11))!!.toList())
        // A second full wrap-around.
        assertEquals(expectedWindow(9, 10, 11, 12), window.update(hopOf(12))!!.toList())
    }

    @Test
    fun `update rejects a hop of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            window.update(ShortArray(config.hopSize - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            window.update(ShortArray(config.hopSize + 1))
        }
    }

    @Test
    fun `sample window exposes no way to write through to internal storage`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val result = window.update(hopOf(4))!!

        // SampleWindow only declares a getter — there is no setter or mutable
        // accessor to call here, which is itself the guarantee. Reading the same
        // index repeatedly must be side-effect free.
        val firstRead = result.toList()
        val secondRead = result.toList()
        assertEquals(firstRead, secondRead)
    }

    @Test
    fun `sample window rejects an out-of-range index`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val result = window.update(hopOf(4))!!

        assertThrows(IndexOutOfBoundsException::class.java) { result[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { result[config.analysisWindowSize] }
    }

    @Test
    fun `reset clears accumulated samples so the window must fill again`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))

        window.reset()

        assertNull(window.update(hopOf(4)))
        assertNull(window.update(hopOf(5)))
        assertNull(window.update(hopOf(6)))
        val result = window.update(hopOf(7))!!

        assertEquals(expectedWindow(4, 5, 6, 7), result.toList())
    }

    @Test
    fun `reset fully clears ring-buffer state, leaving no trace of pre-reset hops`() {
        window.update(hopOf(9))
        window.update(hopOf(9))
        window.update(hopOf(9))
        window.update(hopOf(9))
        window.update(hopOf(9))

        window.reset()

        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val result = window.update(hopOf(4))!!

        assertEquals(expectedWindow(1, 2, 3, 4), result.toList())
    }

    @Test
    fun `a window equal in size to one hop returns that hop immediately`() {
        val singleHopWindow = RollingAnalysisWindow(
            AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 1024)
        )

        val result = singleHopWindow.update(hopOf(7))!!

        assertEquals(List(1024) { 7.toShort() }, result.toList())
    }
}
