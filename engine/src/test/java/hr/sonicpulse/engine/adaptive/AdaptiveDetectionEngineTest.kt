package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdaptiveDetectionEngineTest {

    // hopSize=analysisWindowSize=100: power reflects only the current hop's own samples,
    // no multi-hop averaging, which keeps the numeric scenarios below easy to reason about.
    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 100,
        backgroundHistoryMillis = 500,   // L=5
        variationHistoryMillis = 500,    // D=5
        maxEventDurationMillis = 2000,   // generous - not the focus of these tests
        cooldownMillis = 200,            // D=2 hops
        endSilenceHops = 3
    )

    private fun uniformHop(amplitude: Int): ShortArray = ShortArray(config.hopSize) { amplitude.toShort() }

    private fun mixedHop(bulkValue: Int, spikeValue: Int, spikeCount: Int): ShortArray {
        val hop = ShortArray(config.hopSize) { bulkValue.toShort() }
        for (i in 0 until spikeCount) hop[i] = spikeValue.toShort()
        return hop
    }

    private fun warmedUpEngine(): AdaptiveDetectionEngine {
        val engine = AdaptiveDetectionEngine(config)
        // Quiet, uniform (non-impulsive) hops with mild natural variation - fills L then D
        // without ever triggering, so the engine leaves IDLE with a ready adaptive threshold.
        val quietAmplitudes = listOf(95, 100, 105, 90, 110)
        repeat(15) { i -> engine.process(uniformHop(quietAmplitudes[i % quietAmplitudes.size])) }
        return engine
    }

    @Test
    fun `a loud but non-impulsive hop does not trigger`() {
        val engine = warmedUpEngine()

        // Uniform (crest=0dB) and unclipped: energyExceeded should be true, but not impulsive.
        engine.process(uniformHop(25_000))

        assertEquals(AdaptiveDetectionState.IDLE, engine.state)
    }

    @Test
    fun `an impulsive but insufficiently loud hop does not trigger`() {
        val engine = warmedUpEngine()

        // High crest (spike among near-silent samples), but power far below any plausible
        // adaptive threshold built from the quiet background above.
        engine.process(mixedHop(bulkValue = 5, spikeValue = 150, spikeCount = 3))

        assertEquals(AdaptiveDetectionState.IDLE, engine.state)
    }

    @Test
    fun `a loud and impulsive hop triggers - opens DETECTING`() {
        val engine = warmedUpEngine()

        // Loud (well above threshold) AND impulsive via clipping (3 of 100 samples clipped).
        engine.process(mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3))

        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)
    }

    @Test
    fun `background and variation history are frozen during DETECTING and COOLDOWN`() {
        val engine = warmedUpEngine()
        val spike = mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)

        // Trigger the event.
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)

        // Feed more copies of the same loud spike while DETECTING/COOLDOWN. If background
        // admission were NOT frozen here, these would drag the background median far
        // upward, and the same spike would very likely stop exceeding the (now
        // contaminated) threshold afterward.
        repeat(6) { engine.process(spike) }

        // 3 quiet hops to end the event (endSilenceHops=3) via the normal release path.
        val quiet = uniformHop(100)
        engine.process(quiet)
        engine.process(quiet)
        val event = engine.process(quiet)
        assertNotNull(event)
        assertEquals(AdaptiveDetectionState.COOLDOWN, engine.state)

        // cooldownHops=2: feed loud spikes here too - must also be frozen, not admitted.
        engine.process(spike)
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.IDLE, engine.state)

        // Back in IDLE: since background/variation were never contaminated by the spikes
        // above, the exact same spike must be able to trigger again, unchanged.
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)
    }

    @Test
    fun `the triggering hop itself is admitted into neither history`() {
        val engine = warmedUpEngine()
        val spike = mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)

        engine.process(spike)
        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)

        // End the event and return to IDLE via the normal release + cooldown path.
        val quiet = uniformHop(100)
        engine.process(quiet)
        engine.process(quiet)
        engine.process(quiet)
        assertEquals(AdaptiveDetectionState.COOLDOWN, engine.state)
        engine.process(quiet)
        engine.process(quiet)
        assertEquals(AdaptiveDetectionState.IDLE, engine.state)

        // If the triggering hop itself had been admitted, background would already reflect
        // one loud sample; instead, the same spike must still trigger identically again,
        // exactly as it did the first time.
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)
    }

    @Test
    fun `reset returns the engine to IDLE with no leaked state`() {
        val engine = warmedUpEngine()
        val spike = mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.DETECTING, engine.state)

        engine.reset()

        assertEquals(AdaptiveDetectionState.IDLE, engine.state)

        // Background/analysis window must also be cleared: the very next hop cannot yet
        // trigger anything (no ready background), even though the same spike previously
        // opened an event on a warmed-up engine.
        engine.process(spike)
        assertEquals(AdaptiveDetectionState.IDLE, engine.state)
    }
}
