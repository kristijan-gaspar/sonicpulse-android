package hr.sonicpulse.app.detection

import hr.sonicpulse.app.observability.FakeDetectionSessionLogger
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingAdaptiveDetectionProcessorTest {

    // hopSize=analysisWindowSize=100: power reflects only the current block's own samples,
    // matching the engine-level test config style, keeping trigger scenarios easy to reason about.
    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 100,
        backgroundHistoryMillis = 500,   // L=5
        variationHistoryMillis = 500,    // D=5
        variationWarmupMillis = 500,     // matched to D=5 so the warmup below reaches isReady
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
    fun `delegates sampleRate and blockSize to the wrapped processor`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, FakeDetectionSessionLogger())

        assertEquals(delegate.sampleRate, decorator.sampleRate)
        assertEquals(delegate.blockSize, decorator.blockSize)
    }

    @Test
    fun `returns the exact DetectionProcessingResult the delegate produced`() {
        // Two freshly constructed processors from identical config, fed the identical first
        // hop, must produce identical results — proving the decorator passes the delegate's
        // own result through unchanged rather than building/altering its own.
        val direct = AdaptiveDetectionProcessor(config)
        val delegate = AdaptiveDetectionProcessor(config)
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, FakeDetectionSessionLogger())
        val block = uniformBlock(100)

        val directResult = direct.process(block)
        val decoratedResult = decorator.process(block)

        assertEquals(directResult, decoratedResult)
    }

    @Test
    fun `logs the same-hop diagnostics for a successfully processed hop`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)

        decorator.process(uniformBlock(100))

        assertEquals(1, logger.hops.size)
        val (loggedDiagnostics, loggedEvent) = logger.hops.single()
        assertEquals(delegate.lastDiagnostics, loggedDiagnostics)
        assertNull(loggedEvent)
    }

    @Test
    fun `passes the DetectionEvent returned for that exact same hop`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)
        val quiet = uniformBlock(100)
        val spike = mixedBlock(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)

        val quietAmplitudes = listOf(95, 100, 105, 90, 110)
        repeat(15) { i -> decorator.process(uniformBlock(quietAmplitudes[i % quietAmplitudes.size])) }
        decorator.process(spike)
        decorator.process(quiet)
        decorator.process(quiet)
        val closingResult = decorator.process(quiet)

        assertNotNull(closingResult.event)
        val (_, loggedEvent) = logger.hops.last()
        assertEquals(closingResult.event, loggedEvent)
    }

    @Test
    fun `logs warm-up diagnostics too, with analysisReady false`() {
        val fillUpConfig = config.copy(analysisWindowSize = 400)
        val delegate = AdaptiveDetectionProcessor(fillUpConfig)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)

        decorator.process(uniformBlock(20_000))

        val (diagnostics, event) = logger.hops.single()
        assertEquals(false, diagnostics.analysisReady)
        assertNull(event)
    }

    @Test
    fun `calls the logger exactly once per successfully processed hop`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)

        repeat(5) { decorator.process(uniformBlock(100)) }

        assertEquals(5, logger.hops.size)
    }

    @Test
    fun `never reuses diagnostics from a previous hop`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)

        decorator.process(uniformBlock(100))
        decorator.process(uniformBlock(200))

        assertEquals(0L, logger.hops[0].first.hopIndex)
        assertEquals(1L, logger.hops[1].first.hopIndex)
        assertTrue(logger.hops[0].first != logger.hops[1].first)
    }

    @Test
    fun `does not call the logger if the delegate throws, and the exception propagates unchanged`() {
        val delegate = AdaptiveDetectionProcessor(config)
        val logger = FakeDetectionSessionLogger()
        val decorator = LoggingAdaptiveDetectionProcessor(delegate, logger)
        val wrongSizedBlock = ShortArray(config.hopSize + 1)

        assertThrows(IllegalArgumentException::class.java) {
            decorator.process(wrongSizedBlock)
        }

        assertEquals(0, logger.hops.size)
    }
}
