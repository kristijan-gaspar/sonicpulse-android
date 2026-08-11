package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `update returns null while fewer than analysisWindowSize samples have accumulated`() {
        assertNull(window.update(hopOf(1)))
        assertNull(window.update(hopOf(2)))
        assertNull(window.update(hopOf(3)))
    }

    @Test
    fun `update returns the full window once analysisWindowSize samples have accumulated`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val result = window.update(hopOf(4))

        val expected = ShortArray(4096).also {
            hopOf(1).copyInto(it, 0)
            hopOf(2).copyInto(it, 1024)
            hopOf(3).copyInto(it, 2048)
            hopOf(4).copyInto(it, 3072)
        }
        assertArrayEquals(expected, result)
    }

    @Test
    fun `update slides the window by one hop, dropping the oldest hop`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        window.update(hopOf(4))
        val result = window.update(hopOf(5))

        val expected = ShortArray(4096).also {
            hopOf(2).copyInto(it, 0)
            hopOf(3).copyInto(it, 1024)
            hopOf(4).copyInto(it, 2048)
            hopOf(5).copyInto(it, 3072)
        }
        assertArrayEquals(expected, result)
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
    fun `returned window is a copy, mutating it does not affect subsequent windows`() {
        window.update(hopOf(1))
        window.update(hopOf(2))
        window.update(hopOf(3))
        val first = window.update(hopOf(4))!!
        first.fill(99)

        val second = window.update(hopOf(5))!!

        val expected = ShortArray(4096).also {
            hopOf(2).copyInto(it, 0)
            hopOf(3).copyInto(it, 1024)
            hopOf(4).copyInto(it, 2048)
            hopOf(5).copyInto(it, 3072)
        }
        assertArrayEquals(expected, second)
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
        val result = window.update(hopOf(7))

        val expected = ShortArray(4096).also {
            hopOf(4).copyInto(it, 0)
            hopOf(5).copyInto(it, 1024)
            hopOf(6).copyInto(it, 2048)
            hopOf(7).copyInto(it, 3072)
        }
        assertArrayEquals(expected, result)
    }

    @Test
    fun `a window equal in size to one hop returns that hop immediately`() {
        val singleHopWindow = RollingAnalysisWindow(
            AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 1024)
        )

        val result = singleHopWindow.update(hopOf(7))

        assertArrayEquals(hopOf(7), result)
    }
}
