package hr.sonicpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineConfigTest {

    @Test
    fun `default values match algorithm document section 1_9`() {
        val config = EngineConfig()

        assertEquals(44_100, config.sampleRate)
        assertEquals(1024, config.blockSize)
        assertEquals(0.10, config.alphaDown, 0.0)
        assertEquals(0.02, config.alphaUp, 0.0)
        assertEquals(-20.0, config.dbfsMin, 0.0)
        assertEquals(15.0, config.spikeMin, 0.0)
        assertEquals(10.0, config.crestMin, 0.0)
        assertEquals(3, config.crestWindowBlocks)
        assertEquals(32_000, config.clipLevel)
        assertEquals(0.02, config.clipRatioMin, 0.0)
        assertEquals(3, config.endSilenceBlocks)
        assertEquals(30, config.cooldownBlocks)
        assertEquals(43, config.warmupBlocks)
        assertEquals(-120.0, config.dbfsFloor, 0.0)
    }
}