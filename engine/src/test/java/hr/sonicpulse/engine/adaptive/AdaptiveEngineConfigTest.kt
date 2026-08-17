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
        assertEquals(5.0, config.initialThaStdMultiplier, 0.0)
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
    fun `rejects non-positive initialThaStdMultiplier`() {
        assertRejected { AdaptiveEngineConfig(initialThaStdMultiplier = 0.0) }
        assertRejected { AdaptiveEngineConfig(initialThaStdMultiplier = -5.0) }
    }

    @Test
    fun `default ov is 1_5 and default variationHistoryMillis is 18500`() {
        val config = AdaptiveEngineConfig()

        assertEquals(1.5, config.ov, 0.0)
        assertEquals(18_500, config.variationHistoryMillis)
    }

    @Test
    fun `default variation history capacity (D) is approximately 797`() {
        val config = AdaptiveEngineConfig()

        assertEquals(797, config.variationHistoryCapacity)
    }

    @Test
    fun `default variationWarmupMillis is 5000 and default variationWarmupCapacity is approximately 216`() {
        val config = AdaptiveEngineConfig()

        assertEquals(5000, config.variationWarmupMillis)
        assertEquals(216, config.variationWarmupCapacity)
    }

    @Test
    fun `default L and D capacities no longer coincide - D is a much longer retention window`() {
        val config = AdaptiveEngineConfig()

        assertEquals(216, config.backgroundHistoryCapacity)
        assertEquals(797, config.variationHistoryCapacity)
        assertTrue(config.variationHistoryCapacity > config.backgroundHistoryCapacity)
    }

    @Test
    fun `backgroundHistoryMillis and variationHistoryMillis are independently configurable`() {
        val defaultConfig = AdaptiveEngineConfig()

        val differentL = AdaptiveEngineConfig(backgroundHistoryMillis = 2000)
        assertEquals(defaultConfig.variationHistoryCapacity, differentL.variationHistoryCapacity)
        assertTrue(differentL.backgroundHistoryCapacity < defaultConfig.backgroundHistoryCapacity)

        // variationWarmupMillis must be lowered alongside a smaller D here, to satisfy
        // variationWarmupCapacity <= variationHistoryCapacity.
        val differentD = AdaptiveEngineConfig(variationHistoryMillis = 2000, variationWarmupMillis = 2000)
        assertEquals(defaultConfig.backgroundHistoryCapacity, differentD.backgroundHistoryCapacity)
        assertTrue(differentD.variationHistoryCapacity < defaultConfig.variationHistoryCapacity)
    }

    @Test
    fun `rejects a variationWarmupCapacity greater than variationHistoryCapacity`() {
        assertRejected { AdaptiveEngineConfig(variationHistoryMillis = 1000, variationWarmupMillis = 2000) }
    }

    @Test
    fun `accepts variationWarmupCapacity exactly equal to variationHistoryCapacity`() {
        val config = AdaptiveEngineConfig(variationHistoryMillis = 1000, variationWarmupMillis = 1000)

        assertEquals(config.variationHistoryCapacity, config.variationWarmupCapacity)
    }

    @Test
    fun `rejects non-positive ov`() {
        assertRejected { AdaptiveEngineConfig(ov = 0.0) }
        assertRejected { AdaptiveEngineConfig(ov = -1.5) }
    }

    @Test
    fun `rejects non-positive variationHistoryMillis`() {
        assertRejected { AdaptiveEngineConfig(variationHistoryMillis = 0) }
        assertRejected { AdaptiveEngineConfig(variationHistoryMillis = -1) }
    }

    @Test
    fun `default detection parameter values are set correctly`() {
        val config = AdaptiveEngineConfig()

        assertEquals(10.0, config.crestMinDb, 0.0)
        assertEquals(32_000, config.clipLevel)
        assertEquals(0.02, config.clipRatioMin, 0.0)
        assertEquals(3, config.endSilenceHops)
        assertEquals(300, config.maxEventDurationMillis)
        assertEquals(700, config.cooldownMillis)
    }

    @Test
    fun `default maxEventDurationHops and cooldownHops are both 31`() {
        val config = AdaptiveEngineConfig()

        // ceil(700ms * 44100Hz / (1000 * 1024 samples-per-hop)) = ceil(30.146...) = 31.
        assertEquals(13, config.maxEventDurationHops)
        assertEquals(31, config.cooldownHops)
    }

    @Test
    fun `rejects an out-of-range crestMinDb or clipLevel or clipRatioMin`() {
        assertRejected { AdaptiveEngineConfig(crestMinDb = -1.0) }
        assertRejected { AdaptiveEngineConfig(clipLevel = 0) }
        assertRejected { AdaptiveEngineConfig(clipLevel = 40_000) }
        assertRejected { AdaptiveEngineConfig(clipRatioMin = -0.1) }
        assertRejected { AdaptiveEngineConfig(clipRatioMin = 1.1) }
    }

    @Test
    fun `rejects non-positive endSilenceHops, maxEventDurationMillis or cooldownMillis`() {
        assertRejected { AdaptiveEngineConfig(endSilenceHops = 0) }
        assertRejected { AdaptiveEngineConfig(maxEventDurationMillis = 0) }
        assertRejected { AdaptiveEngineConfig(cooldownMillis = 0) }
    }

    @Test
    fun `accepts rejectedCooldownHops of exactly 0 - the state machine treats it as direct-to-IDLE`() {
        val config = AdaptiveEngineConfig(rejectedCooldownHops = 0)

        assertEquals(0, config.rejectedCooldownHops)
    }

    @Test
    fun `rejects a negative rejectedCooldownHops`() {
        assertRejected { AdaptiveEngineConfig(rejectedCooldownHops = -1) }
    }
}
