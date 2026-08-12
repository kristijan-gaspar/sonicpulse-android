package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `default background history capacity is 216`() {
        val config = AdaptiveEngineConfig()

        assertEquals(216, config.backgroundHistoryCapacity)
    }

    @Test
    fun `background history capacity changes with sample rate, hop size and duration`() {
        val config = AdaptiveEngineConfig(
            sampleRate = 16_000,
            hopSize = 512,
            analysisWindowSize = 2048,
            backgroundHistoryMillis = 2000
        )

        // 2000ms * 16_000Hz / 512 samples-per-hop = 62.5 hops, rounded up.
        val expected = kotlin.math.ceil(2000.0 * 16_000 / (1000.0 * 512)).toInt()
        assertEquals(63, expected)
        assertEquals(expected, config.backgroundHistoryCapacity)
    }

    @Test
    fun `derived capacity always covers at least the configured duration`() {
        val configs = listOf(
            AdaptiveEngineConfig(),
            AdaptiveEngineConfig(sampleRate = 16_000, hopSize = 512, analysisWindowSize = 2048, backgroundHistoryMillis = 2000),
            AdaptiveEngineConfig(sampleRate = 8_000, hopSize = 256, analysisWindowSize = 1024, backgroundHistoryMillis = 1),
            AdaptiveEngineConfig(sampleRate = 44_100, hopSize = 1024, analysisWindowSize = 4096, backgroundHistoryMillis = 5001)
        )

        for (config in configs) {
            val coveredMillis = config.backgroundHistoryCapacity.toLong() * config.hopSize * 1000L
            val requiredMillis = config.backgroundHistoryMillis.toLong() * config.sampleRate
            assertTrue(
                "capacity=${config.backgroundHistoryCapacity} must cover at least " +
                    "${config.backgroundHistoryMillis}ms for sampleRate=${config.sampleRate}, " +
                    "hopSize=${config.hopSize}",
                coveredMillis >= requiredMillis
            )
        }
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
}
