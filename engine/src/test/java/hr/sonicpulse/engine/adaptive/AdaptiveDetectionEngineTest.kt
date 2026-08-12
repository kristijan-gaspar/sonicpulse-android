package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        variationWarmupMillis = 500,     // matched to D=5 so warmedUpEngine() reaches isReady
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
    fun `a loud and impulsive hop does not trigger while the adaptive model is still bootstrapping`() {
        // variationWarmupMillis far beyond L: background readies at hop 5, but isReady
        // (gated by variationWarmupCapacity=50) stays false throughout this test's warmup.
        val slowWarmupConfig = config.copy(variationWarmupMillis = 5000)
        val engine = AdaptiveDetectionEngine(slowWarmupConfig)
        val quietAmplitudes = listOf(95, 100, 105, 90, 110)
        repeat(15) { i -> engine.process(uniformHop(quietAmplitudes[i % quietAmplitudes.size])) }
        assertEquals(true, engine.lastDiagnostics!!.isBootstrapping)

        // Loud and impulsive enough to trigger many times over once ready - must still not
        // open DETECTING while the adaptive model is bootstrapping.
        engine.process(mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3))

        assertEquals(AdaptiveDetectionState.IDLE, engine.state)
    }

    @Test
    fun `energy and crest exceed their own thresholds but the 18dB relative-rise pre-gate still blocks the trigger`() {
        val engine = warmedUpEngine()

        // bulkValue=0 keeps average power well below the mfa*10^(18/10) relative-rise gate,
        // while the few spiking samples alone are still enough to exceed both the adaptive
        // energy threshold (close to mfa for this stable, quiet background) and the crest
        // criterion.
        engine.process(mixedHop(bulkValue = 0, spikeValue = 2_500, spikeCount = 3))
        val diagnostics = engine.lastDiagnostics!!

        assertEquals(false, diagnostics.isBootstrapping)
        assertEquals(true, diagnostics.energyExceeded)
        assertEquals(true, diagnostics.impulsive)
        assertEquals(false, diagnostics.trigger) // blocked by the missing 18dB relative rise
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

    @Test
    fun `lastDbfs is updated from the first hop, even while the analysis window is still filling`() {
        // analysisWindowSize (400) > hopSize (100): the window needs 4 hops to fill, so the
        // first 3 process() calls return null with no power/trigger decision possible yet.
        val fillUpConfig = config.copy(analysisWindowSize = 400)
        val engine = AdaptiveDetectionEngine(fillUpConfig)

        engine.process(uniformHop(1000))
        val afterFirstHop = engine.lastDbfs

        engine.process(uniformHop(5000))
        val afterSecondHop = engine.lastDbfs

        // Both hops are within the window fill-up phase (window not full until hop 4), yet
        // lastDbfs already reflects each hop's own level, not a stale/default value.
        assertEquals(true, afterFirstHop > -120.0)
        assertEquals(true, afterSecondHop > afterFirstHop)
    }

    @Test
    fun `reset clears lastDbfs back to the dBFS floor`() {
        val engine = AdaptiveDetectionEngine(config)
        engine.process(uniformHop(20_000))
        assertEquals(true, engine.lastDbfs > -120.0)

        engine.reset()

        assertEquals(-120.0, engine.lastDbfs, 0.0)
    }

    @Test
    fun `lastDiagnostics corresponds to the exact hop just processed`() {
        val engine = AdaptiveDetectionEngine(config)

        engine.process(uniformHop(100))
        assertEquals(0L, engine.lastDiagnostics!!.hopIndex)

        engine.process(uniformHop(200))
        val second = engine.lastDiagnostics!!
        assertEquals(1L, second.hopIndex)
        assertEquals(engine.lastDbfs, second.dbfs, 0.0)
    }

    @Test
    fun `warm-up diagnostics report analysisReady=false with the actual dbfs and every other field null`() {
        // analysisWindowSize (400) > hopSize (100): window not full until hop 4.
        val fillUpConfig = config.copy(analysisWindowSize = 400)
        val engine = AdaptiveDetectionEngine(fillUpConfig)

        engine.process(uniformHop(20_000))
        val diagnostics = engine.lastDiagnostics!!

        assertEquals(0L, diagnostics.hopIndex)
        assertEquals(false, diagnostics.analysisReady)
        assertEquals(engine.lastDbfs, diagnostics.dbfs, 0.0)
        assertEquals(true, diagnostics.dbfs > -120.0) // genuinely available, not fabricated
        assertNull(diagnostics.power)
        assertNull(diagnostics.crestDb)
        assertNull(diagnostics.clipRatio)
        assertNull(diagnostics.mfa)
        assertNull(diagnostics.variation)
        assertNull(diagnostics.th)
        assertNull(diagnostics.threshold)
        assertNull(diagnostics.isBootstrapping)
        assertNull(diagnostics.energyExceeded)
        assertNull(diagnostics.impulsive)
        assertNull(diagnostics.trigger)
        assertEquals(AdaptiveDetectionState.IDLE, diagnostics.stateBefore)
        assertEquals(AdaptiveDetectionState.IDLE, diagnostics.stateAfter)
    }

    @Test
    fun `once analysis is ready, decision fields are copied unchanged for a loud, non-impulsive hop`() {
        val engine = warmedUpEngine()

        engine.process(uniformHop(25_000))
        val diagnostics = engine.lastDiagnostics!!

        assertEquals(true, diagnostics.analysisReady)
        assertNotNull(diagnostics.power)
        assertEquals(true, diagnostics.energyExceeded)
        assertEquals(false, diagnostics.impulsive) // uniform hop -> crest 0dB, no clipping
        assertEquals(false, diagnostics.trigger)
    }

    @Test
    fun `once analysis is ready, decision fields are copied unchanged for an impulsive but insufficiently loud hop`() {
        val engine = warmedUpEngine()

        engine.process(mixedHop(bulkValue = 5, spikeValue = 150, spikeCount = 3))
        val diagnostics = engine.lastDiagnostics!!

        assertEquals(false, diagnostics.energyExceeded)
        assertEquals(true, diagnostics.impulsive)
        assertEquals(false, diagnostics.trigger) // energy criterion not met
    }

    @Test
    fun `once analysis is ready, decision fields are copied unchanged for a loud and impulsive triggering hop`() {
        val engine = warmedUpEngine()

        engine.process(mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3))
        val diagnostics = engine.lastDiagnostics!!

        assertEquals(true, diagnostics.energyExceeded)
        assertEquals(true, diagnostics.impulsive)
        assertEquals(true, diagnostics.trigger)
        assertEquals(AdaptiveDetectionState.IDLE, diagnostics.stateBefore)
        assertEquals(AdaptiveDetectionState.DETECTING, diagnostics.stateAfter)
    }

    @Test
    fun `stateBefore and stateAfter reflect the state machine transition around the process call`() {
        val engine = warmedUpEngine()
        val spike = mixedHop(bulkValue = 25_000, spikeValue = 32_500, spikeCount = 3)

        // Triggering hop: IDLE -> DETECTING.
        engine.process(spike)
        val onset = engine.lastDiagnostics!!
        assertEquals(AdaptiveDetectionState.IDLE, onset.stateBefore)
        assertEquals(AdaptiveDetectionState.DETECTING, onset.stateAfter)

        // Still-active hop: DETECTING -> DETECTING (no transition).
        engine.process(spike)
        val stillDetecting = engine.lastDiagnostics!!
        assertEquals(AdaptiveDetectionState.DETECTING, stillDetecting.stateBefore)
        assertEquals(AdaptiveDetectionState.DETECTING, stillDetecting.stateAfter)
    }

    @Test
    fun `reset clears lastDiagnostics back to null`() {
        val engine = AdaptiveDetectionEngine(config)
        engine.process(uniformHop(20_000))
        assertNotNull(engine.lastDiagnostics)

        engine.reset()

        assertNull(engine.lastDiagnostics)
    }
}
