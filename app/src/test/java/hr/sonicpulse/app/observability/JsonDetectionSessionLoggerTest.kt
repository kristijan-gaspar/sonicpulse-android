package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveCandidateCompletion
import hr.sonicpulse.engine.adaptive.AdaptiveCandidateRejectionReason
import hr.sonicpulse.engine.adaptive.AdaptiveDetectionState
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDetectionSessionLoggerTest {

    private val config = AdaptiveEngineConfig()

    /** Mirrors [JsonDetectionSessionLogger]'s private `MAX_RECORDED_HOPS_PER_SESSION` — kept as
     * a literal here (the constant is intentionally private) rather than duplicating the
     * engine's own calculation. */
    private val maxRecordedHops = 20_000

    private fun diagnostics(
        hopIndex: Long,
        analysisReady: Boolean = true,
        dbfs: Double = -60.0,
        power: Double? = 0.001,
        crestDb: Double? = 5.0,
        clipRatio: Double? = 0.0,
        backgroundSampleCount: Int = 216,
        mfa: Double? = 0.001,
        stdPower: Double? = 0.0001,
        cmfa: Double? = 0.001,
        tha: Double? = 0.0005,
        variation: Double? = 0.0,
        th: Double? = 0.0005,
        threshold: Double? = 0.0015,
        isBootstrapping: Boolean? = false,
        energyExceeded: Boolean? = false,
        crestExceeded: Boolean? = false,
        clipExceeded: Boolean? = false,
        impulsive: Boolean? = false,
        trigger: Boolean? = false,
        stateBefore: AdaptiveDetectionState = AdaptiveDetectionState.IDLE,
        stateAfter: AdaptiveDetectionState = AdaptiveDetectionState.IDLE,
        activeEventThreshold: Double? = null,
        candidateCompletion: AdaptiveCandidateCompletion? = null
    ) = AdaptiveHopDiagnostics(
        hopIndex = hopIndex,
        analysisReady = analysisReady,
        dbfs = dbfs,
        power = power,
        crestDb = crestDb,
        clipRatio = clipRatio,
        backgroundSampleCount = backgroundSampleCount,
        mfa = mfa,
        stdPower = stdPower,
        cmfa = cmfa,
        tha = tha,
        variation = variation,
        th = th,
        threshold = threshold,
        isBootstrapping = isBootstrapping,
        energyExceeded = energyExceeded,
        crestExceeded = crestExceeded,
        clipExceeded = clipExceeded,
        impulsive = impulsive,
        trigger = trigger,
        stateBefore = stateBefore,
        stateAfter = stateAfter,
        activeEventThreshold = activeEventThreshold,
        candidateCompletion = candidateCompletion
    )

    private fun JsonDetectionSessionLogger.startTestSession(config: AdaptiveEngineConfig = this@JsonDetectionSessionLoggerTest.config) =
        startSession(config, manufacturer = "TestCo", model = "TestPhone", sdkInt = 34)

    private fun decode(json: String) = Json.decodeFromString(SessionLogDocument.serializer(), json)

    // --- session start and finish ---

    @Test
    fun `no completed session exists before finishSession is called`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `finishSession produces a completed, exportable session`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        assertNotNull(logger.exportJson())
    }

    // --- per-hop trace, including trigger=false hops (false-negative visibility) ---

    @Test
    fun `hops that never trigger are still retained in the exported trace`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        repeat(10) { logger.onBlock(diagnostics(it.toLong(), trigger = false)) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(10, document.hops.size)
        assertTrue(document.hops.all { it.trigger == false })
        assertTrue(document.hops.all { it.completion == null })
    }

    @Test
    fun `each retained hop corresponds to the exact diagnostics passed to onBlock, in order`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(diagnostics(hopIndex = 0, dbfs = -30.0))
        logger.onBlock(diagnostics(hopIndex = 1, dbfs = -25.0))
        logger.onBlock(diagnostics(hopIndex = 2, dbfs = -20.0))
        logger.finishSession()

        val hops = decode(requireNotNull(logger.exportJson())).hops
        assertEquals(listOf(0L, 1L, 2L), hops.map { it.hopIndex })
        assertEquals(listOf(-30.0, -25.0, -20.0), hops.map { it.dbfs })
    }

    // --- warm-up hops: nullable fields round-trip as null, never fabricated ---

    @Test
    fun `a warm-up hop (analysisReady=false) round-trips its null fields, not fabricated zeros`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(
            diagnostics(
                hopIndex = 0,
                analysisReady = false,
                power = null,
                mfa = null,
                stdPower = null,
                cmfa = null,
                tha = null,
                variation = null,
                th = null,
                threshold = null,
                isBootstrapping = null,
                energyExceeded = null,
                crestExceeded = null,
                clipExceeded = null,
                impulsive = null,
                trigger = null
            )
        )
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals(false, hop.analysisReady)
        assertNull(hop.power)
        assertNull(hop.mfa)
        assertNull(hop.stdPower)
        assertNull(hop.trigger)
        // dbfs/crestDb/clipRatio are genuinely available even during warm-up.
        assertNotNull(hop.dbfs)
    }

    // --- stdPower / bootstrap exposure ---

    @Test
    fun `stdPower and isBootstrapping are exported for a bootstrap-phase hop`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(diagnostics(hopIndex = 0, isBootstrapping = true, stdPower = 0.002))
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals(true, hop.isBootstrapping)
        assertEquals(0.002, hop.stdPower!!, 0.0)
    }

    @Test
    fun `stdPower remains exported once the robust history is ready, even though it no longer drives the threshold`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(diagnostics(hopIndex = 0, isBootstrapping = false, stdPower = 0.002, th = 0.05, threshold = 0.06))
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals(false, hop.isBootstrapping)
        assertEquals(0.002, hop.stdPower!!, 0.0) // still present, diagnostics-only at this point
        assertEquals(0.05, hop.th!!, 0.0)
    }

    // --- decision fields round-trip exactly ---

    @Test
    fun `energyExceeded, crestExceeded, clipExceeded, impulsive and trigger round-trip exactly`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(
            diagnostics(
                hopIndex = 0,
                energyExceeded = true,
                crestExceeded = false,
                clipExceeded = true,
                impulsive = true,
                trigger = true
            )
        )
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals(true, hop.energyExceeded)
        assertEquals(false, hop.crestExceeded)
        assertEquals(true, hop.clipExceeded)
        assertEquals(true, hop.impulsive)
        assertEquals(true, hop.trigger)
    }

    // --- state transitions ---

    @Test
    fun `stateBefore and stateAfter are captured without alteration`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(
            diagnostics(
                hopIndex = 0,
                stateBefore = AdaptiveDetectionState.IDLE,
                stateAfter = AdaptiveDetectionState.DETECTING,
                activeEventThreshold = null
            )
        )
        logger.onBlock(
            diagnostics(
                hopIndex = 1,
                stateBefore = AdaptiveDetectionState.DETECTING,
                stateAfter = AdaptiveDetectionState.DETECTING,
                activeEventThreshold = 0.02
            )
        )
        logger.finishSession()

        val hops = decode(requireNotNull(logger.exportJson())).hops
        assertEquals("IDLE", hops[0].stateBefore)
        assertEquals("DETECTING", hops[0].stateAfter)
        assertEquals("DETECTING", hops[1].stateBefore)
        assertEquals(0.02, hops[1].activeEventThreshold!!, 0.0)
    }

    // --- candidate completions embedded on the finishing hop ---

    @Test
    fun `an accepted completion is embedded on exactly the hop it finished on`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        val event = DetectionEvent(peakDbfs = -15.0, peakBlockIndex = 2, durationBlocks = 3)
        logger.onBlock(diagnostics(hopIndex = 0))
        logger.onBlock(diagnostics(hopIndex = 1))
        logger.onBlock(
            diagnostics(hopIndex = 2, candidateCompletion = AdaptiveCandidateCompletion.Accepted(event))
        )
        logger.finishSession()

        val hops = decode(requireNotNull(logger.exportJson())).hops
        assertNull(hops[0].completion)
        assertNull(hops[1].completion)
        val completion = hops[2].completion!!
        assertEquals("ACCEPTED", completion.outcome)
        assertNull(completion.rejectionReason)
        assertEquals(-15.0, completion.peakDbfs, 0.0)
        assertEquals(2L, completion.peakBlockIndex)
        assertEquals(3, completion.durationHops)
    }

    @Test
    fun `a TOO_LONG rejection is embedded with rejectionReason TOO_LONG and no accepted-style event fields missing`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(
            diagnostics(
                hopIndex = 0,
                candidateCompletion = AdaptiveCandidateCompletion.Rejected(
                    reason = AdaptiveCandidateRejectionReason.TOO_LONG,
                    peakDbfs = -5.0,
                    peakBlockIndex = 12,
                    durationHops = 31
                )
            )
        )
        logger.finishSession()

        val completion = decode(requireNotNull(logger.exportJson())).hops.single().completion!!
        assertEquals("REJECTED", completion.outcome)
        assertEquals("TOO_LONG", completion.rejectionReason)
        assertEquals(-5.0, completion.peakDbfs, 0.0)
        assertEquals(12L, completion.peakBlockIndex)
        assertEquals(31, completion.durationHops)
    }

    // --- bounded retention ---

    @Test
    fun `hops beyond the retention limit stop being retained in detail while totalHopCount keeps counting`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        val totalHops = maxRecordedHops + 5
        for (i in 0 until totalHops) {
            logger.onBlock(diagnostics(i.toLong()))
        }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(totalHops, document.totalHopCount)
        assertEquals(maxRecordedHops, document.recordedHopCount)
        assertEquals(maxRecordedHops, document.hops.size)
        assertEquals(true, document.hopsTruncated)
    }

    @Test
    fun `hopsTruncated stays false and totalHopCount equals recordedHopCount while every hop is retained`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        repeat(5) { logger.onBlock(diagnostics(it.toLong())) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(5, document.totalHopCount)
        assertEquals(5, document.recordedHopCount)
        assertEquals(false, document.hopsTruncated)
    }

    // --- idempotent finish/stop handling ---

    @Test
    fun `finishSession is a no-op when no session is in progress`() {
        val logger = JsonDetectionSessionLogger()

        logger.finishSession() // never started

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `calling finishSession twice does not duplicate or corrupt the completed session`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))

        logger.finishSession()
        val firstExport = logger.exportJson()
        logger.finishSession() // redundant second call, as a defensive teardown path would make
        val secondExport = logger.exportJson()

        assertEquals(firstExport, secondExport)
        assertEquals(1, decode(requireNotNull(secondExport)).hops.size)
    }

    // --- replacement of an active/completed session (prepared vs. genuinely activated) ---

    @Test
    fun `starting a new session discards an unfinished previous session's in-progress data`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        logger.onBlock(diagnostics(0)) // never finalized

        logger.startTestSession() // replaces the abandoned in-progress session
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(1, document.hops.size)
    }

    @Test
    fun `starting a new session does not discard the previous completed session until the new one activates`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()
        assertEquals(true, logger.hasCompletedSession.value)
        val previousJson = requireNotNull(logger.exportJson())

        logger.startTestSession() // only prepared — no hop has arrived for it yet

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(previousJson, logger.exportJson())
    }

    @Test
    fun `the first real hop after startSession activates it, only then replacing the previous completed session`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()
        check(logger.hasCompletedSession.value)

        logger.startTestSession()
        logger.onBlock(diagnostics(0)) // the genuine activation

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `a capture attempt that never activates preserves the previous completed session through finishSession`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()
        val previousJson = requireNotNull(logger.exportJson())

        logger.startTestSession() // e.g. AudioRecorder is about to start
        logger.finishSession() // capture failed (or was stopped) before any hop ever arrived

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(previousJson, logger.exportJson())
    }

    // --- valid JSON serialization ---

    @Test
    fun `exported JSON is valid, pretty-printed and round-trips with schema version exactly 4`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        assertTrue("expected pretty-printed output to contain newlines", json.contains("\n"))
        val document = decode(json) // throws on invalid JSON
        assertEquals(4, SESSION_LOG_SCHEMA_VERSION)
        assertEquals(SESSION_LOG_SCHEMA_VERSION, document.schemaVersion)
    }

    @Test
    fun `exported JSON never contains raw audio sample data`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        listOf("pcm", "wav", "samples", "audioData", "shortArray").forEach { forbidden ->
            assertTrue("unexpected raw-audio-like field: $forbidden", !json.contains(forbidden, ignoreCase = true))
        }
    }

    // --- correct AdaptiveEngineConfig snapshot ---

    @Test
    fun `the exported engineConfig snapshot matches every field of the actual V2 config passed to startSession`() {
        val customConfig = AdaptiveEngineConfig(
            sampleRate = 48_000,
            hopSize = 2048,
            analysisWindowSize = 8192,
            backgroundHistoryMillis = 6000,
            thresholdStdMultiplier = 4.0,
            variationHistoryMillis = 4000,
            ov = 1.8,
            crestMinDb = 9.0,
            clipLevel = 30_000,
            clipRatioMin = 0.03,
            endSilenceHops = 4,
            maxEventDurationMillis = 600,
            cooldownMillis = 500
        )
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(customConfig)
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        val snapshot = decode(requireNotNull(logger.exportJson())).engineConfig

        assertEquals(customConfig.sampleRate, snapshot.sampleRate)
        assertEquals(customConfig.hopSize, snapshot.hopSize)
        assertEquals(customConfig.analysisWindowSize, snapshot.analysisWindowSize)
        assertEquals(customConfig.backgroundHistoryMillis, snapshot.backgroundHistoryMillis)
        assertEquals(customConfig.backgroundHistoryCapacity, snapshot.backgroundHistoryCapacity)
        assertEquals(customConfig.thresholdStdMultiplier, snapshot.thresholdStdMultiplier, 0.0)
        assertEquals(customConfig.variationHistoryMillis, snapshot.variationHistoryMillis)
        assertEquals(customConfig.variationHistoryCapacity, snapshot.variationHistoryCapacity)
        assertEquals(customConfig.ov, snapshot.ov, 0.0)
        assertEquals(customConfig.crestMinDb, snapshot.crestMinDb, 0.0)
        assertEquals(customConfig.clipLevel, snapshot.clipLevel)
        assertEquals(customConfig.clipRatioMin, snapshot.clipRatioMin, 0.0)
        assertEquals(customConfig.endSilenceHops, snapshot.endSilenceHops)
        assertEquals(customConfig.maxEventDurationMillis, snapshot.maxEventDurationMillis)
        assertEquals(customConfig.maxEventDurationHops, snapshot.maxEventDurationHops)
        assertEquals(customConfig.cooldownMillis, snapshot.cooldownMillis)
        assertEquals(customConfig.cooldownHops, snapshot.cooldownHops)
    }

    // --- device info ---

    @Test
    fun `device info is captured from the parameters passed to startSession, never real Build fields`() {
        val logger = JsonDetectionSessionLogger()
        logger.startSession(config, manufacturer = "Acme", model = "Widget", sdkInt = 30)
        logger.onBlock(diagnostics(0))
        logger.finishSession()

        val device = decode(requireNotNull(logger.exportJson())).device

        assertEquals("Acme", device.manufacturer)
        assertEquals("Widget", device.model)
        assertEquals(30, device.sdkInt)
    }
}
