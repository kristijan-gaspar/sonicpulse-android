package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.DetectionState
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDetectionSessionLoggerTest {

    private val config = EngineConfig()

    /** Mirrors [JsonDetectionSessionLogger]'s private `MAX_DETAILED_EVENT_BLOCKS` — kept as a
     * literal here (the constant is intentionally private) rather than duplicating the engine's
     * own calculation. */
    private val maxDetailedEventBlocks = 100

    private fun metrics(
        blockIndex: Long,
        dbfs: Double = -60.0,
        baseline: Double = -60.0,
        spike: Double = 0.0,
        crest: Double? = 5.0,
        clipRatio: Double = 0.0,
        state: DetectionState = DetectionState.IDLE
    ) = BlockMetrics(
        rms = 0.0,
        dbfs = dbfs,
        baseline = baseline,
        spike = spike,
        crest = crest,
        clipRatio = clipRatio,
        state = state,
        blockIndex = blockIndex
    )

    private fun JsonDetectionSessionLogger.startTestSession(config: EngineConfig = this@JsonDetectionSessionLoggerTest.config) =
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
        logger.onBlock(metrics(0), null)
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        assertNotNull(logger.exportJson())
    }

    // --- session with zero detections ---

    @Test
    fun `a session with zero detections is still exportable with an empty detections array`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        repeat(10) { logger.onBlock(metrics(it.toLong()), null) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertTrue(document.detections.isEmpty())
    }

    @Test
    fun `a genuinely activated session with zero detections remains exportable`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        logger.onBlock(metrics(0, state = DetectionState.IDLE), null) // the one block that activates it
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        val document = decode(requireNotNull(logger.exportJson()))
        assertTrue(document.detections.isEmpty())
    }

    // --- event metric aggregation ---

    @Test
    fun `max dbfs, spike, crest and clipRatio reflect only the event's own blocks, not pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Pre-event context: louder than anything in the actual event, to prove it's excluded
        // from the max* aggregates (it must still appear in the block list itself, though).
        logger.onBlock(metrics(0, dbfs = -1.0, spike = 999.0, crest = 999.0, clipRatio = 0.99, state = DetectionState.IDLE), null)

        logger.onBlock(metrics(1, dbfs = -20.0, spike = 30.0, crest = 10.0, clipRatio = 0.0, state = DetectionState.DETECTING), null)
        logger.onBlock(metrics(2, dbfs = -15.0, spike = 40.0, crest = 12.0, clipRatio = 0.1, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -15.0, peakBlockIndex = 2)
        val finalized = FinalizedEvent(event, Instant.parse("2026-01-01T00:00:00Z"))
        logger.onBlock(metrics(3, dbfs = -18.0, spike = 20.0, crest = 8.0, clipRatio = 0.05, state = DetectionState.COOLDOWN), finalized)

        logger.finishSession()
        val entry = decode(requireNotNull(logger.exportJson())).detections.single()

        assertEquals(-15.0, entry.maxDbfs, 0.0)
        assertEquals(40.0, entry.maxSpike, 0.0)
        assertEquals(12.0, entry.maxCrestFactorDb!!, 0.0)
        assertEquals(0.1, entry.maxClipRatio, 0.0)
        // A short, normal event must never be reported as truncated.
        assertEquals(false, entry.blocksTruncated)
        assertEquals(3, entry.totalEventBlockCount)
        assertEquals(entry.blocks.size, entry.recordedBlockCount)
    }

    // --- pre-event ring-buffer behavior ---

    @Test
    fun `only the last PRE_EVENT_CONTEXT_BLOCKS idle blocks are kept as pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Far more IDLE blocks than the documented ring-buffer size (5).
        repeat(20) { logger.onBlock(metrics(it.toLong(), state = DetectionState.IDLE), null) }

        logger.onBlock(metrics(20, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 20)
        logger.onBlock(metrics(21, state = DetectionState.COOLDOWN), FinalizedEvent(event, Instant.EPOCH))
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).detections.single()
        // 5 pre-event context blocks (indices 15..19) + 2 event blocks (20, 21).
        assertEquals(7, entry.recordedBlockCount)
        assertEquals(entry.recordedBlockCount, entry.blocks.size)
        assertEquals(15L, entry.blocks.first().blockIndex)
        assertEquals(21L, entry.blocks.last().blockIndex)
    }

    @Test
    fun `a second event starting right after the first never inherits stale pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Fills the ring buffer with blocks that belong to *before* the first event.
        repeat(5) { logger.onBlock(metrics(it.toLong(), state = DetectionState.IDLE), null) } // indices 0..4

        // First event starts and finishes with no IDLE block in between (as with a short or zero
        // cooldown configuration) — the ring buffer must be cleared the moment it starts.
        logger.onBlock(metrics(5, state = DetectionState.DETECTING), null)
        val firstEvent = DetectionEvent(peakDbfs = -12.0, peakBlockIndex = 5)
        logger.onBlock(metrics(6, state = DetectionState.COOLDOWN), FinalizedEvent(firstEvent, Instant.parse("2026-01-01T00:00:00Z")))

        // Second event starts immediately — still no IDLE block since the first event began, so
        // the ring buffer would still hold the stale 0..4 blocks if it were never cleared.
        logger.onBlock(metrics(7, state = DetectionState.DETECTING), null)
        val secondEvent = DetectionEvent(peakDbfs = -8.0, peakBlockIndex = 7)
        logger.onBlock(metrics(8, state = DetectionState.COOLDOWN), FinalizedEvent(secondEvent, Instant.parse("2026-01-01T00:00:05Z")))

        logger.finishSession()
        val detections = decode(requireNotNull(logger.exportJson())).detections
        val secondEntry = detections[1]

        assertTrue("second event must not contain any block from before it started", secondEntry.blocks.none { it.blockIndex < 7 })
        assertEquals(2, secondEntry.blocks.size)
        assertEquals(7L, secondEntry.blocks.first().blockIndex)
    }

    // --- bounded retained event detail ---

    @Test
    fun `an event longer than the detailed-block cap truncates retained blocks but keeps full duration and maximums`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        val totalEventBlocks = maxDetailedEventBlocks + 50
        // The loudest block is deliberately the very last one — well past the cap — to prove
        // aggregate figures keep updating from every block, not just the retained detail records.
        for (i in 0 until totalEventBlocks) {
            val isLast = i == totalEventBlocks - 1
            val metrics = metrics(
                blockIndex = i.toLong(),
                dbfs = if (isLast) -1.0 else -50.0,
                state = if (isLast) DetectionState.COOLDOWN else DetectionState.DETECTING
            )
            val finalized = if (isLast) {
                FinalizedEvent(DetectionEvent(peakDbfs = -1.0, peakBlockIndex = (totalEventBlocks - 1).toLong()), Instant.EPOCH)
            } else {
                null
            }
            logger.onBlock(metrics, finalized)
        }
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).detections.single()

        assertEquals(totalEventBlocks, entry.totalEventBlockCount)
        assertEquals(true, entry.blocksTruncated)
        // No pre-event context here (the event starts on the very first block of the test), so
        // recordedBlockCount is exactly the detail cap.
        assertEquals(maxDetailedEventBlocks, entry.recordedBlockCount)
        assertEquals(maxDetailedEventBlocks, entry.blocks.size)
        // Full span, not just the retained detail records.
        assertEquals(totalEventBlocks, entry.durationBlocks)
        // The peak block (past the cap) is still reflected in the aggregate maximum.
        assertEquals(-1.0, entry.maxDbfs, 0.0)
    }

    // --- multiple detections in one session ---

    @Test
    fun `two separate events in the same session both appear, in order`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val firstEvent = DetectionEvent(peakDbfs = -12.0, peakBlockIndex = 0)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), FinalizedEvent(firstEvent, Instant.parse("2026-01-01T00:00:00Z")))
        logger.onBlock(metrics(2, state = DetectionState.IDLE), null)

        logger.onBlock(metrics(3, state = DetectionState.DETECTING), null)
        val secondEvent = DetectionEvent(peakDbfs = -8.0, peakBlockIndex = 3)
        logger.onBlock(metrics(4, state = DetectionState.COOLDOWN), FinalizedEvent(secondEvent, Instant.parse("2026-01-01T00:00:05Z")))

        logger.finishSession()
        val detections = decode(requireNotNull(logger.exportJson())).detections

        assertEquals(2, detections.size)
        assertEquals(-12.0, detections[0].peakDbfs, 0.0)
        assertEquals(-8.0, detections[1].peakDbfs, 0.0)
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
        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), FinalizedEvent(event, Instant.EPOCH))

        logger.finishSession()
        val firstExport = logger.exportJson()
        logger.finishSession() // redundant second call, as a defensive teardown path would make
        val secondExport = logger.exportJson()

        assertEquals(firstExport, secondExport)
        assertEquals(1, decode(requireNotNull(secondExport)).detections.size)
    }

    // --- replacement of an active/completed session (prepared vs. genuinely activated) ---

    @Test
    fun `starting a new session discards an unfinished previous session's in-progress data`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null) // never finalized

        logger.startTestSession() // replaces the abandoned in-progress session
        logger.onBlock(metrics(0, state = DetectionState.IDLE), null)
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertTrue(document.detections.isEmpty())
    }

    @Test
    fun `starting a new session does not discard the previous completed session until the new one activates`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0), null)
        logger.finishSession()
        assertEquals(true, logger.hasCompletedSession.value)
        val previousJson = requireNotNull(logger.exportJson())

        logger.startTestSession() // only prepared — no block has arrived for it yet

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(previousJson, logger.exportJson())
    }

    @Test
    fun `the first real block after startSession activates it, only then replacing the previous completed session`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0), null)
        logger.finishSession()
        check(logger.hasCompletedSession.value)

        logger.startTestSession()
        logger.onBlock(metrics(0, state = DetectionState.IDLE), null) // the genuine activation

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `a capture attempt that never activates preserves the previous completed session through finishSession`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0), null)
        logger.finishSession()
        val previousJson = requireNotNull(logger.exportJson())

        logger.startTestSession() // e.g. AudioRecorder is about to start
        logger.finishSession() // capture failed (or was stopped) before any block ever arrived

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(previousJson, logger.exportJson())
    }

    // --- valid JSON serialization ---

    @Test
    fun `exported JSON is valid, pretty-printed and round-trips`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0), null)
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        assertTrue("expected pretty-printed output to contain newlines", json.contains("\n"))
        val document = decode(json) // throws on invalid JSON
        assertEquals(SESSION_LOG_SCHEMA_VERSION, document.schemaVersion)
    }

    // --- correct EngineConfig snapshot ---

    @Test
    fun `the exported engineConfig snapshot matches every field of the EngineConfig passed to startSession`() {
        val customConfig = EngineConfig(
            sampleRate = 48_000,
            blockSize = 2048,
            alphaDown = 0.2,
            alphaUp = 0.05,
            dbfsMin = -18.0,
            spikeMin = 12.0,
            crestMin = 8.0,
            crestWindowBlocks = 4,
            clipLevel = 30_000,
            clipRatioMin = 0.03,
            endSilenceBlocks = 4,
            cooldownBlocks = 20,
            warmupBlocks = 30,
            dbfsFloor = -100.0
        )
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(customConfig)
        logger.onBlock(metrics(0), null)
        logger.finishSession()

        val snapshot = decode(requireNotNull(logger.exportJson())).engineConfig

        assertEquals(customConfig.sampleRate, snapshot.sampleRate)
        assertEquals(customConfig.blockSize, snapshot.blockSize)
        assertEquals(customConfig.alphaDown, snapshot.alphaDown, 0.0)
        assertEquals(customConfig.alphaUp, snapshot.alphaUp, 0.0)
        assertEquals(customConfig.dbfsMin, snapshot.dbfsMin, 0.0)
        assertEquals(customConfig.spikeMin, snapshot.spikeMin, 0.0)
        assertEquals(customConfig.crestMin, snapshot.crestMin, 0.0)
        assertEquals(customConfig.crestWindowBlocks, snapshot.crestWindowBlocks)
        assertEquals(customConfig.clipLevel, snapshot.clipLevel)
        assertEquals(customConfig.clipRatioMin, snapshot.clipRatioMin, 0.0)
        assertEquals(customConfig.endSilenceBlocks, snapshot.endSilenceBlocks)
        assertEquals(customConfig.cooldownBlocks, snapshot.cooldownBlocks)
        assertEquals(customConfig.warmupBlocks, snapshot.warmupBlocks)
        assertEquals(customConfig.dbfsFloor, snapshot.dbfsFloor, 0.0)
    }

    // --- duration calculation from sampleRate and blockSize ---

    @Test
    fun `event duration in blocks and milliseconds is derived from sampleRate and blockSize`() {
        // 1024 / 44100 s per block = ~23.2199 ms/block; 3 event blocks (indices 10..12).
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(EngineConfig(sampleRate = 44_100, blockSize = 1024))

        logger.onBlock(metrics(10, state = DetectionState.DETECTING), null)
        logger.onBlock(metrics(11, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 11)
        logger.onBlock(metrics(12, state = DetectionState.COOLDOWN), FinalizedEvent(event, Instant.EPOCH))
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).detections.single()

        val expectedBlockDurationMillis = 1024 * 1000.0 / 44_100
        assertEquals(3, entry.durationBlocks)
        assertEquals((3 * expectedBlockDurationMillis).toLong(), entry.durationMillis)
    }

    @Test
    fun `relativeToPeakMillis is negative before the peak block and zero at it`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(EngineConfig(sampleRate = 44_100, blockSize = 1024))

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), FinalizedEvent(event, Instant.EPOCH))
        logger.finishSession()

        val blocks = decode(requireNotNull(logger.exportJson())).detections.single().blocks
        val peakBlock = blocks.first { it.blockIndex == 0L }
        val afterPeakBlock = blocks.first { it.blockIndex == 1L }

        assertEquals(0L, peakBlock.relativeToPeakMillis)
        assertTrue(afterPeakBlock.relativeToPeakMillis > 0L)
    }

    // --- device info ---

    @Test
    fun `device info is captured from the parameters passed to startSession, never real Build fields`() {
        val logger = JsonDetectionSessionLogger()
        logger.startSession(config, manufacturer = "Acme", model = "Widget", sdkInt = 30)
        logger.onBlock(metrics(0), null)
        logger.finishSession()

        val device = decode(requireNotNull(logger.exportJson())).device

        assertEquals("Acme", device.manufacturer)
        assertEquals("Widget", device.model)
        assertEquals(30, device.sdkInt)
    }
}
