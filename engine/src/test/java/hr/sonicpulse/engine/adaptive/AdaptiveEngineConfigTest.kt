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
}
