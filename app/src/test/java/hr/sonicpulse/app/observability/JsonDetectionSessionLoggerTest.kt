package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.DetectionEvent
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
    private val maxRecordedHops = 5_000

    private fun newLogger(config: AdaptiveEngineConfig = this.config) = JsonDetectionSessionLogger(config)

    private fun diagnostics(
        hopIndex: Long,
        analysisReady: Boolean = true,
        dbfs: Double = -60.0,
        power: Double? = 0.001,
        crestDb: Double? = 5.0,
        clipRatio: Double? = 0.0,
        mfa: Double? = 0.001,
        variation: Double? = 0.0,
        th: Double? = 0.0005,
        threshold: Double? = 0.0015,
        isBootstrapping: Boolean? = false,
        energyExceeded: Boolean? = false,
        impulsive: Boolean? = false,
        trigger: Boolean? = false,
        stateBefore: AdaptiveDetectionState = AdaptiveDetectionState.IDLE,
        stateAfter: AdaptiveDetectionState = AdaptiveDetectionState.IDLE
    ) = AdaptiveHopDiagnostics(
        hopIndex = hopIndex,
        analysisReady = analysisReady,
        dbfs = dbfs,
        power = power,
        crestDb = crestDb,
        clipRatio = clipRatio,
        mfa = mfa,
        variation = variation,
        th = th,
        threshold = threshold,
        isBootstrapping = isBootstrapping,
        energyExceeded = energyExceeded,
        impulsive = impulsive,
        trigger = trigger,
        stateBefore = stateBefore,
        stateAfter = stateAfter
    )

    private fun JsonDetectionSessionLogger.startTestSession() =
        startSession(manufacturer = "TestCo", model = "TestPhone", sdkInt = 34)

    private fun decode(json: String) = Json.decodeFromString(SessionLogDocument.serializer(), json)

    // --- session start and finish ---

    @Test
    fun `no completed session exists before finishSession is called`() {
        val logger = newLogger()

        logger.startTestSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `finishSession produces a completed, exportable session`() {
        val logger = newLogger()

        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        assertNotNull(logger.exportJson())
    }

    // --- per-hop trace, including trigger=false hops (false-negative visibility) ---

    @Test
    fun `hops that never trigger are still retained in the exported trace`() {
        val logger = newLogger()
        logger.startTestSession()

        repeat(10) { logger.onHop(diagnostics(it.toLong(), trigger = false), null) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(10, document.hops.size)
        assertTrue(document.hops.all { it.trigger == false })
        assertTrue(document.hops.all { it.event == null })
    }

    @Test
    fun `each retained hop corresponds to the exact diagnostics passed to onHop, in order`() {
        val logger = newLogger()
        logger.startTestSession()

        logger.onHop(diagnostics(hopIndex = 0, dbfs = -30.0), null)
        logger.onHop(diagnostics(hopIndex = 1, dbfs = -25.0), null)
        logger.onHop(diagnostics(hopIndex = 2, dbfs = -20.0), null)
        logger.finishSession()

        val hops = decode(requireNotNull(logger.exportJson())).hops
        assertEquals(listOf(0L, 1L, 2L), hops.map { it.hopIndex })
        assertEquals(listOf(-30.0, -25.0, -20.0), hops.map { it.dbfs })
    }

    // --- warm-up hops: nullable fields round-trip as null, never fabricated ---

    @Test
    fun `a warm-up hop (analysisReady=false) round-trips its null fields, not fabricated zeros`() {
        val logger = newLogger()
        logger.startTestSession()

        logger.onHop(
            diagnostics(
                hopIndex = 0,
                analysisReady = false,
                power = null,
                mfa = null,
                variation = null,
                th = null,
                threshold = null,
                isBootstrapping = null,
                energyExceeded = null,
                impulsive = null,
                trigger = null
            ),
            null
        )
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals(false, hop.analysisReady)
        assertNull(hop.power)
        assertNull(hop.mfa)
        assertNull(hop.variation)
        assertNull(hop.th)
        assertNull(hop.threshold)
        assertNull(hop.isBootstrapping)
        assertNull(hop.energyExceeded)
        assertNull(hop.impulsive)
        assertNull(hop.trigger)
        assertNotNull(hop.dbfs) // genuinely available, not fabricated
    }

    // --- state transitions ---

    @Test
    fun `stateBefore and stateAfter are serialized as enum name strings`() {
        val logger = newLogger()
        logger.startTestSession()

        logger.onHop(
            diagnostics(
                hopIndex = 0,
                stateBefore = AdaptiveDetectionState.IDLE,
                stateAfter = AdaptiveDetectionState.DETECTING
            ),
            null
        )
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertEquals("IDLE", hop.stateBefore)
        assertEquals("DETECTING", hop.stateAfter)
    }

    // --- optional accepted DetectionEvent ---

    @Test
    fun `an accepted DetectionEvent is serialized with its exact peakDbfs, peakBlockIndex and durationBlocks`() {
        val logger = newLogger()
        logger.startTestSession()

        val event = DetectionEvent(peakDbfs = -15.0, peakBlockIndex = 42, durationBlocks = 3)
        logger.onHop(diagnostics(hopIndex = 0, trigger = true), event)
        logger.finishSession()

        val hopEvent = decode(requireNotNull(logger.exportJson())).hops.single().event!!
        assertEquals(-15.0, hopEvent.peakDbfs, 0.0)
        assertEquals(42L, hopEvent.peakBlockIndex)
        assertEquals(3, hopEvent.durationBlocks)
    }

    @Test
    fun `a hop with no closing event serializes event as null`() {
        val logger = newLogger()
        logger.startTestSession()

        logger.onHop(diagnostics(hopIndex = 0), null)
        logger.finishSession()

        val hop = decode(requireNotNull(logger.exportJson())).hops.single()
        assertNull(hop.event)
    }

    // --- bounded retention ---

    @Test
    fun `hops beyond the retention limit stop being retained in detail while totalHopCount keeps counting`() {
        val logger = newLogger()
        logger.startTestSession()

        val totalHops = maxRecordedHops + 5
        for (i in 0 until totalHops) {
            logger.onHop(diagnostics(i.toLong()), null)
        }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(totalHops, document.totalHopCount)
        assertEquals(maxRecordedHops, document.recordedHopCount)
        assertEquals(maxRecordedHops, document.hops.size)
        assertEquals(true, document.hopsTruncated)
    }

    @Test
    fun `a hop past the retention limit is correctly discarded even when it carries a closing DetectionEvent`() {
        val logger = newLogger()
        logger.startTestSession()

        repeat(maxRecordedHops) { logger.onHop(diagnostics(it.toLong()), null) }
        // One more hop, past the cap, that would need both diagnostics and event mapping if
        // it were retained — must still be counted, but neither the hop nor its event may
        // leak into the retained trace.
        val overflowEvent = DetectionEvent(peakDbfs = -1.0, peakBlockIndex = 999, durationBlocks = 5)
        logger.onHop(diagnostics(maxRecordedHops.toLong(), trigger = true), overflowEvent)
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(maxRecordedHops + 1, document.totalHopCount)
        assertEquals(maxRecordedHops, document.recordedHopCount)
        assertEquals(maxRecordedHops, document.hops.size)
        assertEquals(true, document.hopsTruncated)
        assertTrue(document.hops.none { it.hopIndex == maxRecordedHops.toLong() })
        assertTrue(document.hops.none { it.event != null })
    }

    @Test
    fun `hopsTruncated stays false and totalHopCount equals recordedHopCount while every hop is retained`() {
        val logger = newLogger()
        logger.startTestSession()

        repeat(5) { logger.onHop(diagnostics(it.toLong()), null) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(5, document.totalHopCount)
        assertEquals(5, document.recordedHopCount)
        assertEquals(false, document.hopsTruncated)
    }

    // --- idempotent finish/stop handling ---

    @Test
    fun `finishSession is a no-op when no session is in progress`() {
        val logger = newLogger()

        logger.finishSession() // never started

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `calling finishSession twice does not duplicate or corrupt the completed session`() {
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)

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
        val logger = newLogger()

        logger.startTestSession()
        logger.onHop(diagnostics(0), null) // never finalized

        logger.startTestSession() // replaces the abandoned in-progress session
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertEquals(1, document.hops.size)
    }

    @Test
    fun `starting a new session does not discard the previous completed session until the new one activates`() {
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()
        assertEquals(true, logger.hasCompletedSession.value)
        val previousJson = requireNotNull(logger.exportJson())

        logger.startTestSession() // only prepared — no hop has arrived for it yet

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(previousJson, logger.exportJson())
    }

    @Test
    fun `the first real hop after startSession activates it, only then replacing the previous completed session`() {
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()
        check(logger.hasCompletedSession.value)

        logger.startTestSession()
        logger.onHop(diagnostics(0), null) // the genuine activation

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `a capture attempt that never activates preserves the previous completed session through finishSession`() {
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
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
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        assertTrue("expected pretty-printed output to contain newlines", json.contains("\n"))
        val document = decode(json) // throws on invalid JSON
        assertEquals(4, SESSION_LOG_SCHEMA_VERSION)
        assertEquals(SESSION_LOG_SCHEMA_VERSION, document.schemaVersion)
    }

    @Test
    fun `exported JSON never contains raw audio sample data`() {
        val logger = newLogger()
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        listOf("pcm", "wav", "samples", "audioData", "shortArray").forEach { forbidden ->
            assertTrue("unexpected raw-audio-like field: $forbidden", !json.contains(forbidden, ignoreCase = true))
        }
    }

    // --- correct AdaptiveEngineConfig snapshot ---

    @Test
    fun `the exported adaptiveEngineConfig snapshot matches every field of the injected config`() {
        val customConfig = AdaptiveEngineConfig(
            sampleRate = 48_000,
            hopSize = 2048,
            analysisWindowSize = 8192,
            backgroundHistoryMillis = 6000,
            initialThaStdMultiplier = 4.0,
            variationHistoryMillis = 4000,
            ov = 1.8,
            crestMinDb = 9.0,
            clipLevel = 30_000,
            clipRatioMin = 0.03,
            endSilenceHops = 4,
            maxEventDurationMillis = 600,
            cooldownMillis = 500
        )
        val logger = newLogger(customConfig)
        logger.startTestSession()
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        val snapshot = decode(requireNotNull(logger.exportJson())).adaptiveEngineConfig

        assertEquals(customConfig.sampleRate, snapshot.sampleRate)
        assertEquals(customConfig.hopSize, snapshot.hopSize)
        assertEquals(customConfig.analysisWindowSize, snapshot.analysisWindowSize)
        assertEquals(customConfig.backgroundHistoryMillis, snapshot.backgroundHistoryMillis)
        assertEquals(customConfig.initialThaStdMultiplier, snapshot.initialThaStdMultiplier, 0.0)
        assertEquals(customConfig.variationHistoryMillis, snapshot.variationHistoryMillis)
        assertEquals(customConfig.ov, snapshot.ov, 0.0)
        assertEquals(customConfig.crestMinDb, snapshot.crestMinDb, 0.0)
        assertEquals(customConfig.clipLevel, snapshot.clipLevel)
        assertEquals(customConfig.clipRatioMin, snapshot.clipRatioMin, 0.0)
        assertEquals(customConfig.endSilenceHops, snapshot.endSilenceHops)
        assertEquals(customConfig.maxEventDurationMillis, snapshot.maxEventDurationMillis)
        assertEquals(customConfig.cooldownMillis, snapshot.cooldownMillis)
    }

    // --- device info ---

    @Test
    fun `device info is captured from the parameters passed to startSession, never real Build fields`() {
        val logger = newLogger()
        logger.startSession(manufacturer = "Acme", model = "Widget", sdkInt = 30)
        logger.onHop(diagnostics(0), null)
        logger.finishSession()

        val device = decode(requireNotNull(logger.exportJson())).device

        assertEquals("Acme", device.manufacturer)
        assertEquals("Widget", device.model)
        assertEquals(30, device.sdkInt)
    }
}
