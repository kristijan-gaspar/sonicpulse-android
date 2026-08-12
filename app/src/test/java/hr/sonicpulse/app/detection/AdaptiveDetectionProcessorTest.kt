package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDetectionProcessorTest {

    // hopSize=analysisWindowSize=100: power reflects only the current block's own samples,
    // matching the engine-level test config style, keeping trigger scenarios easy to reason about.
    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 100,
        backgroundHistoryMillis = 500,   // L=5
        variationHistoryMillis = 500,    // D=5
        maxEventDurationMillis = 2000,
        cooldownMillis = 200,
        endSilenceHops = 3
    )

    private fun uniformBlock(amplitude: Int): ShortArray = ShortArray(config.hopSize) { amplitude.toShort() }

    private fun mixedBlock(bulkValue: Int, spikeValue: Int, spikeCount: Int): ShortArray {
        val block = ShortArray(config.hopSize) { bulkValue.toShort() }
        for (i in 0 until spikeCount) block[i] = spikeValue.toShort()
        return block
    }

    @Test
    fun `exposes sampleRate and blockSize from the supplied AdaptiveEngineConfig`() {
        val processor = AdaptiveDetectionProcessor(config)

        assertEquals(1000, processor.sampleRate)
        assertEquals(100, processor.blockSize)
    }

    @Test
    fun `exposes the exact AdaptiveEngineConfig instance the engine was built from`() {
        val processor = AdaptiveDetectionProcessor(config)

        assertEquals(config, processor.engineConfig)
    }

    @Test
    fun `result diagnostics correspond to the exact hop just processed`() {
        val processor = AdaptiveDetectionProcessor(config)

        val first = processor.process(uniformBlock(100))
        assertEquals(0L, first.diagnostics.hopIndex)
        assertEquals(first.dbfs, first.diagnostics.dbfs, 0.0)

        val second = processor.process(uniformBlock(200))
        assertEquals(1L, second.diagnostics.hopIndex)
        assertEquals(second.dbfs, second.diagnostics.dbfs, 0.0)
    }

    @Test
    fun `diagnostics report analysisReady=false and no fabricated power while the window is filling`() {
        val fillUpConfig = config.copy(analysisWindowSize = 400)
        val processor = AdaptiveDetectionProcessor(fillUpConfig)

        val result = processor.process(uniformBlock(20_000))

        assertEquals(false, result.diagnostics.analysisReady)
        assertNull(result.diagnostics.power)
        assertNull(result.diagnostics.trigger)
    }

    @Test
    fun `uses AdaptiveEngineConfig defaults when none is supplied`() {
        val processor = AdaptiveDetectionProcessor()

        assertEquals(44_100, processor.sampleRate)
        assertEquals(1024, processor.blockSize)
    }

    @Test
    fun `dBFS is available from the first hop, even while the analysis window is still filling`() {
        val fillUpConfig = config.copy(analysisWindowSize = 400)
        val processor = AdaptiveDetectionProcessor(fillUpConfig)

        val result = processor.process(uniformBlock(20_000))

        assertTrue(result.dbfs > -120.0)
        assertNull(result.event) // window not full yet - no decision possible
    }

    @Test
    fun `no event on hops that do not close a candidate`() {
        val processor = AdaptiveDetectionProcessor(config)

        val result = processor.process(uniformBlock(100))

        assertNull(result.event)
    }

    @Test
    fun `process delegates to the adaptive engine and surfaces an accepted DetectionEvent`() {
        val processor = AdaptiveDetectionProcessor(config)
        val quiet = uniformBlock(100)
        val spike = mixedBlock(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)

        // Warm up background/variation history without ever triggering.
        val quietAmplitudes = listOf(95, 100, 105, 90, 110)
        repeat(15) { i -> processor.process(uniformBlock(quietAmplitudes[i % quietAmplitudes.size])) }

        val onset = processor.process(spike)
        assertNull(onset.event)

        processor.process(quiet)
        processor.process(quiet)
        val closing = processor.process(quiet)

        assertNotNull(closing.event)
        assertTrue(closing.event!!.peakDbfs > -120.0)
    }
}
