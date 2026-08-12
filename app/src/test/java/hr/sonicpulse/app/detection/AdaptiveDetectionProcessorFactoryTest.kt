package hr.sonicpulse.app.detection

import hr.sonicpulse.app.observability.FakeDetectionSessionLogger
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDetectionProcessorFactoryTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 100,
        backgroundHistoryMillis = 500,   // L=5
        variationHistoryMillis = 500,    // D=5
        cooldownMillis = 200
    )

    private fun uniformBlock(amplitude: Int): ShortArray = ShortArray(config.hopSize) { amplitude.toShort() }

    private fun mixedBlock(bulkValue: Int, spikeValue: Int, spikeCount: Int): ShortArray {
        val block = ShortArray(config.hopSize) { bulkValue.toShort() }
        for (i in 0 until spikeCount) block[i] = spikeValue.toShort()
        return block
    }

    @Test
    fun `create returns a distinct processor instance each time`() {
        val factory = AdaptiveDetectionProcessorFactory(config, FakeDetectionSessionLogger())

        val first = factory.create()
        val second = factory.create()

        assertNotSame(first, second)
    }

    @Test
    fun `created processors use the factory's config for sampleRate and blockSize`() {
        val factory = AdaptiveDetectionProcessorFactory(config, FakeDetectionSessionLogger())

        val processor = factory.create()

        assertEquals(1000, processor.sampleRate)
        assertEquals(100, processor.blockSize)
    }

    @Test
    fun `create returns a processor whose hops are reported to the injected DetectionSessionLogger`() {
        val logger = FakeDetectionSessionLogger()
        val factory = AdaptiveDetectionProcessorFactory(config, logger)

        val processor = factory.create()
        processor.process(uniformBlock(100))

        assertEquals(1, logger.hops.size)
    }

    @Test
    fun `a freshly created processor carries no stateful adaptive engine state from a previous session`() {
        val factory = AdaptiveDetectionProcessorFactory(config, FakeDetectionSessionLogger())
        val spike = mixedBlock(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)
        val quiet = uniformBlock(100)
        val quietAmplitudes = listOf(95, 100, 105, 90, 110)

        val first = factory.create()

        repeat(15) { i ->
            first.process(uniformBlock(quietAmplitudes[i % quietAmplitudes.size]))
        }

        first.process(spike)


        val second = factory.create()

        val onset = second.process(spike)
        val afterFirstQuiet = second.process(quiet)
        val afterSecondQuiet = second.process(quiet)
        val afterThirdQuiet = second.process(quiet)

        assertNull(onset.event)
        assertNull(afterFirstQuiet.event)
        assertNull(afterSecondQuiet.event)
        assertNull(afterThirdQuiet.event)
    }

    @Test
    fun `separate create() calls each get their own independent logger view of a fresh session`() {
        val logger = FakeDetectionSessionLogger()
        val factory = AdaptiveDetectionProcessorFactory(config, logger)
        val quietAmplitudes = listOf(95, 100, 105, 90, 110)

        val first = factory.create()
        repeat(3) { i -> first.process(uniformBlock(quietAmplitudes[i % quietAmplitudes.size])) }

        val second = factory.create()
        second.process(uniformBlock(100))

        // Both processors report to the same shared logger (by design - it's the DI-selected
        // singleton), but each hop still carries its own processor's independent hopIndex,
        // proving the two underlying engines never shared state.
        assertEquals(4, logger.hops.size)
        assertTrue(logger.hops.count { it.first.hopIndex == 0L } == 2) // one from each fresh engine
    }
}
