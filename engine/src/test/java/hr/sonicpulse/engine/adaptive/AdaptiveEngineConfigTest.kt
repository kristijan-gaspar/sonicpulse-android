package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdaptiveEngineConfigTest {

    @Test
    fun `default parameter values are set correctly`() {
        val config = AdaptiveEngineConfig()

        assertEquals(44_100, config.sampleRate)
        assertEquals(1024, config.hopSize)
        assertEquals(4096, config.analysisWindowSize)
    }

    private fun assertRejected(build: () -> AdaptiveEngineConfig) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun `rejects non-positive sampleRate`() {
        assertRejected { AdaptiveEngineConfig(sampleRate = 0) }
        assertRejected { AdaptiveEngineConfig(sampleRate = -1) }
    }

    @Test
    fun `rejects non-positive hopSize`() {
        assertRejected { AdaptiveEngineConfig(hopSize = 0) }
        assertRejected { AdaptiveEngineConfig(hopSize = -1) }
    }

    @Test
    fun `rejects non-positive analysisWindowSize`() {
        assertRejected { AdaptiveEngineConfig(analysisWindowSize = 0) }
        assertRejected { AdaptiveEngineConfig(analysisWindowSize = -1) }
    }

    @Test
    fun `rejects analysisWindowSize smaller than hopSize`() {
        assertRejected { AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 512) }
    }

    @Test
    fun `rejects analysisWindowSize that is not a whole multiple of hopSize`() {
        assertRejected { AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 4000) }
    }

    @Test
    fun `accepts analysisWindowSize equal to hopSize`() {
        val config = AdaptiveEngineConfig(hopSize = 1024, analysisWindowSize = 1024)

        assertEquals(1024, config.analysisWindowSize)
    }

    @Test
    fun `default background parameter values are set correctly`() {
        val config = AdaptiveEngineConfig()

        assertEquals(5000, config.backgroundHistoryMillis)
        assertEquals(5.0, config.thresholdStdMultiplier, 0.0)
    }

    @Test
    fun `derives the default background history capacity from sample rate, hop size and duration`() {
        val config = AdaptiveEngineConfig()

        // capacity = backgroundHistoryMillis/1000 * sampleRate / hopSize, floored.
        val expected = (5000L * 44_100L) / (1000L * 1024L)
        assertEquals(expected.toInt(), config.backgroundHistoryCapacity)
    }

    @Test
    fun `background history capacity changes with sample rate, hop size and duration`() {
        val config = AdaptiveEngineConfig(
            sampleRate = 16_000,
            hopSize = 512,
            analysisWindowSize = 2048,
            backgroundHistoryMillis = 2000
        )

        val expected = (2000L * 16_000L) / (1000L * 512L)
        assertEquals(expected.toInt(), config.backgroundHistoryCapacity)
    }

    @Test
    fun `rejects non-positive backgroundHistoryMillis`() {
        assertRejected { AdaptiveEngineConfig(backgroundHistoryMillis = 0) }
        assertRejected { AdaptiveEngineConfig(backgroundHistoryMillis = -1) }
    }

    @Test
    fun `rejects non-positive thresholdStdMultiplier`() {
        assertRejected { AdaptiveEngineConfig(thresholdStdMultiplier = 0.0) }
        assertRejected { AdaptiveEngineConfig(thresholdStdMultiplier = -5.0) }
    }

    @Test
    fun `rejects a backgroundHistoryMillis too small to yield at least one observation`() {
        assertRejected {
            AdaptiveEngineConfig(sampleRate = 44_100, hopSize = 1024, backgroundHistoryMillis = 1)
        }
    }
}
