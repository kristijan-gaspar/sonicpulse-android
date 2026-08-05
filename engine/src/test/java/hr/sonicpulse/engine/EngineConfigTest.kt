package hr.sonicpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineConfigTest {

    @Test
    fun `default parameter values are set correctly`() {
        val config = EngineConfig()

        assertEquals(44_100, config.sampleRate)
        assertEquals(1024, config.blockSize)
        assertEquals(0.10, config.alphaDown, 0.0)
        assertEquals(0.02, config.alphaUp, 0.0)
        assertEquals(-20.0, config.dbfsMin, 0.0)
        assertEquals(15.0, config.spikeMin, 0.0)
        assertEquals(20.0, config.releaseDropDb, 0.0)
        assertEquals(10.0, config.crestMin, 0.0)
        assertEquals(3, config.crestWindowBlocks)
        assertEquals(32_000, config.clipLevel)
        assertEquals(0.02, config.clipRatioMin, 0.0)
        assertEquals(3, config.endSilenceBlocks)
        assertEquals(30, config.maxEventDurationBlocks)
        assertEquals(30, config.cooldownBlocks)
        assertEquals(43, config.warmupBlocks)
        assertEquals(-120.0, config.dbfsFloor, 0.0)
    }

    @Test
    fun `cooldownBlocks and warmupBlocks of zero are valid boundary values`() {
        val config = EngineConfig(cooldownBlocks = 0, warmupBlocks = 0)

        assertEquals(0, config.cooldownBlocks)
        assertEquals(0, config.warmupBlocks)
    }

    private fun assertRejected(build: () -> EngineConfig) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun `rejects non-positive sampleRate`() {
        assertRejected { EngineConfig(sampleRate = 0) }
        assertRejected { EngineConfig(sampleRate = -1) }
    }

    @Test
    fun `rejects non-positive blockSize`() {
        assertRejected { EngineConfig(blockSize = 0) }
        assertRejected { EngineConfig(blockSize = -1) }
    }

    @Test
    fun `rejects alphaDown outside the open-closed unit interval`() {
        assertRejected { EngineConfig(alphaDown = 0.0) }
        assertRejected { EngineConfig(alphaDown = -0.1) }
        assertRejected { EngineConfig(alphaDown = 1.1) }
        assertRejected { EngineConfig(alphaDown = Double.NaN) }
    }

    @Test
    fun `rejects alphaUp outside the open-closed unit interval`() {
        assertRejected { EngineConfig(alphaUp = 0.0) }
        assertRejected { EngineConfig(alphaUp = -0.1) }
        assertRejected { EngineConfig(alphaUp = 1.1) }
        assertRejected { EngineConfig(alphaUp = Double.NaN) }
    }

    @Test
    fun `rejects alphaDown not greater than alphaUp`() {
        assertRejected { EngineConfig(alphaDown = 0.02, alphaUp = 0.02) }
        assertRejected { EngineConfig(alphaDown = 0.01, alphaUp = 0.02) }
    }

    @Test
    fun `rejects a non-finite or non-negative dbfsFloor`() {
        assertRejected { EngineConfig(dbfsFloor = 0.0) }
        assertRejected { EngineConfig(dbfsFloor = 5.0) }
        assertRejected { EngineConfig(dbfsFloor = Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `rejects dbfsMin that is not finite, not negative, or not above dbfsFloor`() {
        assertRejected { EngineConfig(dbfsMin = 0.0) }
        assertRejected { EngineConfig(dbfsMin = 5.0) }
        assertRejected { EngineConfig(dbfsMin = -120.0, dbfsFloor = -120.0) }
        assertRejected { EngineConfig(dbfsMin = -130.0, dbfsFloor = -120.0) }
    }

    @Test
    fun `rejects non-positive spikeMin`() {
        assertRejected { EngineConfig(spikeMin = 0.0) }
        assertRejected { EngineConfig(spikeMin = -1.0) }
    }

    @Test
    fun `rejects negative crestMin`() {
        assertRejected { EngineConfig(crestMin = -1.0) }
    }

    @Test
    fun `rejects non-finite releaseDropDb`() {
        assertRejected { EngineConfig(releaseDropDb = Double.NaN) }
        assertRejected { EngineConfig(releaseDropDb = Double.POSITIVE_INFINITY) }
        assertRejected { EngineConfig(releaseDropDb = Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `rejects zero or negative releaseDropDb`() {
        assertRejected { EngineConfig(releaseDropDb = 0.0) }
        assertRejected { EngineConfig(releaseDropDb = -1.0) }
    }

    @Test
    fun `rejects releaseDropDb larger than the available dBFS range`() {
        assertRejected {
            EngineConfig(
                dbfsMin = -20.0,
                dbfsFloor = -120.0,
                releaseDropDb = 100.1
            )
        }
    }

    @Test
    fun `accepts releaseDropDb equal to the available dBFS range`() {
        val config = EngineConfig(
            dbfsMin = -20.0,
            dbfsFloor = -120.0,
            releaseDropDb = 100.0
        )

        assertEquals(100.0, config.releaseDropDb, 0.0)
    }

    @Test
    fun `releaseDropDb upper bound follows configured dbfsMin and dbfsFloor`() {
        assertRejected {
            EngineConfig(
                dbfsMin = -30.0,
                dbfsFloor = -100.0,
                releaseDropDb = 70.1
            )
        }

        val valid = EngineConfig(
            dbfsMin = -30.0,
            dbfsFloor = -100.0,
            releaseDropDb = 70.0
        )

        assertEquals(70.0, valid.releaseDropDb, 0.0)
    }

    @Test
    fun `accepts a releaseDropDb unrelated to spikeMin, in either direction`() {
        // releaseDropDb is a peak-relative dB drop, not a spike threshold — it has no required
        // relationship with spikeMin, unlike the old baseline-relative releaseSpikeMin.
        val lower = EngineConfig(releaseDropDb = 1.0, spikeMin = 15.0)
        val higher = EngineConfig(releaseDropDb = 50.0, spikeMin = 15.0)

        assertEquals(1.0, lower.releaseDropDb, 0.0)
        assertEquals(50.0, higher.releaseDropDb, 0.0)
    }

    @Test
    fun `rejects non-positive crestWindowBlocks`() {
        assertRejected { EngineConfig(crestWindowBlocks = 0) }
        assertRejected { EngineConfig(crestWindowBlocks = -1) }
    }

    @Test
    fun `rejects clipLevel outside the PCM-16 absolute range`() {
        assertRejected { EngineConfig(clipLevel = 0) }
        assertRejected { EngineConfig(clipLevel = -1) }
        assertRejected { EngineConfig(clipLevel = 32_769) }
    }

    @Test
    fun `rejects clipRatioMin outside zero to one`() {
        assertRejected { EngineConfig(clipRatioMin = -0.01) }
        assertRejected { EngineConfig(clipRatioMin = 1.01) }
    }

    @Test
    fun `rejects non-positive endSilenceBlocks`() {
        assertRejected { EngineConfig(endSilenceBlocks = 0) }
        assertRejected { EngineConfig(endSilenceBlocks = -1) }
    }

    @Test
    fun `rejects non-positive maxEventDurationBlocks`() {
        assertRejected { EngineConfig(maxEventDurationBlocks = 0) }
        assertRejected { EngineConfig(maxEventDurationBlocks = -1) }
    }

    @Test
    fun `rejects negative cooldownBlocks`() {
        assertRejected { EngineConfig(cooldownBlocks = -1) }
    }

    @Test
    fun `rejects negative warmupBlocks`() {
        assertRejected { EngineConfig(warmupBlocks = -1) }
    }
}