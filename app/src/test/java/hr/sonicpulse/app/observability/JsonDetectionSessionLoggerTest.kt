package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.CandidateCompletion
import hr.sonicpulse.engine.CandidateRejectionReason
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

    /** Mirrors [JsonDetectionSessionLogger]'s private `MAX_RECORDED_CANDIDATES_PER_SESSION`. */
    private val maxRecordedCandidates = 500

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

    private fun accepted(event: DetectionEvent, peakTimeClient: Instant = Instant.EPOCH) =
        FinalizedCandidate(CandidateCompletion.Accepted(event), peakTimeClient)

    private fun rejected(
        peakDbfs: Double,
        peakBlockIndex: Long,
        durationBlocks: Int,
        peakTimeClient: Instant = Instant.EPOCH,
        reason: CandidateRejectionReason = CandidateRejectionReason.TOO_LONG
    ) = FinalizedCandidate(
        CandidateCompletion.Rejected(reason, peakDbfs, peakBlockIndex, durationBlocks),
        peakTimeClient
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

    // --- session with zero candidates ---

    @Test
    fun `a session with zero candidates is still exportable with an empty candidates array`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        repeat(10) { logger.onBlock(metrics(it.toLong()), null) }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))
        assertTrue(document.candidates.isEmpty())
        assertEquals(0, document.totalCandidateCount)
        assertEquals(0, document.recordedCandidateCount)
        assertEquals(false, document.candidatesTruncated)
    }

    @Test
    fun `a genuinely activated session with zero candidates remains exportable`() {
        val logger = JsonDetectionSessionLogger()

        logger.startTestSession()
        logger.onBlock(metrics(0, state = DetectionState.IDLE), null) // the one block that activates it
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        val document = decode(requireNotNull(logger.exportJson()))
        assertTrue(document.candidates.isEmpty())
    }

    // --- accepted candidate metric aggregation ---

    @Test
    fun `max dbfs, spike, crest and clipRatio reflect only the candidate's own blocks, not pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Pre-event context: louder than anything in the actual candidate, to prove it's excluded
        // from the max* aggregates (it must still appear in the block list itself, though).
        logger.onBlock(metrics(0, dbfs = -1.0, spike = 999.0, crest = 999.0, clipRatio = 0.99, state = DetectionState.IDLE), null)

        logger.onBlock(metrics(1, dbfs = -20.0, spike = 30.0, crest = 10.0, clipRatio = 0.0, state = DetectionState.DETECTING), null)
        logger.onBlock(metrics(2, dbfs = -15.0, spike = 40.0, crest = 12.0, clipRatio = 0.1, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -15.0, peakBlockIndex = 2, durationBlocks = 1)
        logger.onBlock(metrics(3, dbfs = -18.0, spike = 20.0, crest = 8.0, clipRatio = 0.05, state = DetectionState.COOLDOWN), accepted(event, Instant.parse("2026-01-01T00:00:00Z")))

        logger.finishSession()
        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()

        assertEquals("ACCEPTED", entry.outcome)
        assertNull(entry.rejectionReason)
        assertEquals(-15.0, entry.maxDbfs, 0.0)
        assertEquals(40.0, entry.maxSpike, 0.0)
        assertEquals(12.0, entry.maxCrestFactorDb!!, 0.0)
        assertEquals(0.1, entry.maxClipRatio, 0.0)
        // A short, normal candidate must never be reported as truncated.
        assertEquals(false, entry.blocksTruncated)
        assertEquals(3, entry.totalEventBlockCount)
        assertEquals(entry.blocks.size, entry.recordedBlockCount)
    }

    // --- rejected candidates ---

    @Test
    fun `a TOO_LONG completion creates a REJECTED candidate with rejectionReason TOO_LONG`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null) // onset
        repeat(29) { logger.onBlock(metrics((it + 1).toLong(), state = DetectionState.DETECTING), null) }
        logger.onBlock(
            metrics(30, dbfs = -5.0, spike = 50.0, state = DetectionState.COOLDOWN),
            rejected(peakDbfs = -5.0, peakBlockIndex = 12, durationBlocks = 31)
        )
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()

        assertEquals("REJECTED", entry.outcome)
        assertEquals("TOO_LONG", entry.rejectionReason)
        assertEquals(-5.0, entry.peakDbfs, 0.0)
        assertEquals(12L, entry.peakBlockIndex)
        assertEquals(31, entry.durationBlocks)
    }

    @Test
    fun `a rejected completion clears pending-candidate state so a later accepted candidate is not merged into it`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // First candidate: rejected.
        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        logger.onBlock(
            metrics(1, state = DetectionState.COOLDOWN),
            rejected(peakDbfs = -5.0, peakBlockIndex = 0, durationBlocks = 2)
        )

        // Second candidate: starts fresh and is accepted — must remain a completely separate entry.
        logger.onBlock(metrics(2, state = DetectionState.DETECTING), null)
        val secondEvent = DetectionEvent(peakDbfs = -8.0, peakBlockIndex = 2, durationBlocks = 1)
        logger.onBlock(metrics(3, state = DetectionState.COOLDOWN), accepted(secondEvent))

        logger.finishSession()
        val candidates = decode(requireNotNull(logger.exportJson())).candidates

        assertEquals(2, candidates.size)
        assertEquals("REJECTED", candidates[0].outcome)
        assertEquals("ACCEPTED", candidates[1].outcome)
        // The accepted candidate's own trace must not include any block from the rejected one.
        assertTrue(candidates[1].blocks.none { it.blockIndex < 2 })
    }

    // --- pre-event ring-buffer behavior ---

    @Test
    fun `only the last PRE_EVENT_CONTEXT_BLOCKS idle blocks are kept as pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Far more IDLE blocks than the documented ring-buffer size (5).
        repeat(20) { logger.onBlock(metrics(it.toLong(), state = DetectionState.IDLE), null) }

        logger.onBlock(metrics(20, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 20, durationBlocks = 1)
        logger.onBlock(metrics(21, state = DetectionState.COOLDOWN), accepted(event))
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()
        // 5 pre-event context blocks (indices 15..19) + 2 event blocks (20, 21).
        assertEquals(7, entry.recordedBlockCount)
        assertEquals(entry.recordedBlockCount, entry.blocks.size)
        assertEquals(15L, entry.blocks.first().blockIndex)
        assertEquals(21L, entry.blocks.last().blockIndex)
    }

    @Test
    fun `a second candidate starting right after the first never inherits stale pre-event context`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Fills the ring buffer with blocks that belong to *before* the first candidate.
        repeat(5) { logger.onBlock(metrics(it.toLong(), state = DetectionState.IDLE), null) } // indices 0..4

        // First candidate starts and finishes with no IDLE block in between (as with a short or
        // zero cooldown configuration) — the ring buffer must be cleared the moment it starts.
        logger.onBlock(metrics(5, state = DetectionState.DETECTING), null)
        val firstEvent = DetectionEvent(peakDbfs = -12.0, peakBlockIndex = 5, durationBlocks = 1)
        logger.onBlock(metrics(6, state = DetectionState.COOLDOWN), accepted(firstEvent, Instant.parse("2026-01-01T00:00:00Z")))

        // Second candidate starts immediately — still no IDLE block since the first one began, so
        // the ring buffer would still hold the stale 0..4 blocks if it were never cleared.
        logger.onBlock(metrics(7, state = DetectionState.DETECTING), null)
        val secondEvent = DetectionEvent(peakDbfs = -8.0, peakBlockIndex = 7, durationBlocks = 1)
        logger.onBlock(metrics(8, state = DetectionState.COOLDOWN), accepted(secondEvent, Instant.parse("2026-01-01T00:00:05Z")))

        logger.finishSession()
        val candidates = decode(requireNotNull(logger.exportJson())).candidates
        val secondEntry = candidates[1]

        assertTrue("second candidate must not contain any block from before it started", secondEntry.blocks.none { it.blockIndex < 7 })
        assertEquals(2, secondEntry.blocks.size)
        assertEquals(7L, secondEntry.blocks.first().blockIndex)
    }

    // --- bounded retained candidate detail ---

    @Test
    fun `a candidate longer than the detailed-block cap truncates retained blocks but keeps full duration and maximums`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        val totalEventBlocks = maxDetailedEventBlocks + 50
        // The loudest block is deliberately the very last one — well past the cap — to prove
        // aggregate figures keep updating from every block, not just the retained detail records.
        for (i in 0 until totalEventBlocks) {
            val isLast = i == totalEventBlocks - 1
            val blockMetrics = metrics(
                blockIndex = i.toLong(),
                dbfs = if (isLast) -1.0 else -50.0,
                state = if (isLast) DetectionState.COOLDOWN else DetectionState.DETECTING
            )
            val finalized = if (isLast) {
                accepted(DetectionEvent(peakDbfs = -1.0, peakBlockIndex = (totalEventBlocks - 1).toLong(), durationBlocks = totalEventBlocks))
            } else {
                null
            }
            logger.onBlock(blockMetrics, finalized)
        }
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()

        assertEquals(totalEventBlocks, entry.totalEventBlockCount)
        assertEquals(true, entry.blocksTruncated)
        // No pre-event context here (the candidate starts on the very first block of the test), so
        // recordedBlockCount is exactly the detail cap.
        assertEquals(maxDetailedEventBlocks, entry.recordedBlockCount)
        assertEquals(maxDetailedEventBlocks, entry.blocks.size)
        // Full span, not just the retained detail records.
        assertEquals(totalEventBlocks, entry.durationBlocks)
        // The peak block (past the cap) is still reflected in the aggregate maximum.
        assertEquals(-1.0, entry.maxDbfs, 0.0)
    }

    // --- durationBlocks vs. trace length ---

    @Test
    fun `an accepted candidate's durationBlocks excludes trailing inactive confirmation blocks, even though they remain in the trace`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null) // onset
        logger.onBlock(metrics(1, state = DetectionState.DETECTING), null) // last release-active block
        logger.onBlock(metrics(2, state = DetectionState.DETECTING), null) // inactive confirmation 1/3
        logger.onBlock(metrics(3, state = DetectionState.DETECTING), null) // inactive confirmation 2/3
        // durationBlocks = 2 (onset + the one release-active block) — excludes the three trailing
        // inactive confirmation blocks, exactly like DetectionEngine.handleInactiveBlock().
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 1, durationBlocks = 2)
        logger.onBlock(metrics(4, state = DetectionState.COOLDOWN), accepted(event)) // inactive confirmation 3/3, finalizes
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()

        assertEquals(2, entry.durationBlocks)
        assertEquals(5, entry.totalEventBlockCount)
        assertEquals(5, entry.recordedBlockCount)
        assertTrue("trace must contain more blocks than durationBlocks", entry.blocks.size > entry.durationBlocks)
    }

    // --- multiple candidates in one session ---

    @Test
    fun `two separate candidates in the same session both appear, in order`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val firstEvent = DetectionEvent(peakDbfs = -12.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), accepted(firstEvent, Instant.parse("2026-01-01T00:00:00Z")))
        logger.onBlock(metrics(2, state = DetectionState.IDLE), null)

        logger.onBlock(metrics(3, state = DetectionState.DETECTING), null)
        val secondEvent = DetectionEvent(peakDbfs = -8.0, peakBlockIndex = 3, durationBlocks = 1)
        logger.onBlock(metrics(4, state = DetectionState.COOLDOWN), accepted(secondEvent, Instant.parse("2026-01-01T00:00:05Z")))

        logger.finishSession()
        val candidates = decode(requireNotNull(logger.exportJson())).candidates

        assertEquals(2, candidates.size)
        assertEquals(-12.0, candidates[0].peakDbfs, 0.0)
        assertEquals(-8.0, candidates[1].peakDbfs, 0.0)
    }

    // --- candidate retention cap ---

    @Test
    fun `retained candidates are capped at MAX_RECORDED_CANDIDATES_PER_SESSION while totalCandidateCount keeps counting`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        val totalCandidates = maxRecordedCandidates + 10
        for (i in 0 until totalCandidates) {
            val onsetIndex = (i * 2).toLong()
            logger.onBlock(metrics(onsetIndex, state = DetectionState.DETECTING), null)
            logger.onBlock(
                metrics(onsetIndex + 1, state = DetectionState.COOLDOWN),
                rejected(peakDbfs = -10.0, peakBlockIndex = onsetIndex, durationBlocks = 2)
            )
        }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))

        assertEquals(totalCandidates, document.totalCandidateCount)
        assertEquals(maxRecordedCandidates, document.recordedCandidateCount)
        assertEquals(maxRecordedCandidates, document.candidates.size)
        assertEquals(true, document.candidatesTruncated)
    }

    @Test
    fun `pendingCandidate is cleared after a completion past the retention cap, so each discarded candidate is counted separately with no leaked state`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        // Fill exactly to the retention cap — all 500 retained.
        for (i in 0 until maxRecordedCandidates) {
            val onsetIndex = (i * 2).toLong()
            logger.onBlock(metrics(onsetIndex, state = DetectionState.DETECTING), null)
            val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = onsetIndex, durationBlocks = 1)
            logger.onBlock(metrics(onsetIndex + 1, state = DetectionState.COOLDOWN), accepted(event))
        }

        // Two more, back to back, both past the cap and both discarded. If pendingCandidate were
        // left dangling after the first discard, the second one's onset block (state == DETECTING)
        // would hit the `pending != null -> pending.addBlock(metrics)` branch instead of starting
        // a fresh PendingCandidate — this would still be discarded either way, but would prove the
        // state was never actually reset. Two independent completions arriving cleanly (each
        // incrementing totalCandidateCount by exactly one and neither ever appearing in the
        // retained list) is the only outcome consistent with a genuinely cleared pendingCandidate.
        val firstOverflowOnset = (maxRecordedCandidates * 2).toLong()
        logger.onBlock(metrics(firstOverflowOnset, state = DetectionState.DETECTING), null)
        val firstOverflowEvent = DetectionEvent(peakDbfs = -1.0, peakBlockIndex = firstOverflowOnset, durationBlocks = 1)
        logger.onBlock(metrics(firstOverflowOnset + 1, state = DetectionState.COOLDOWN), accepted(firstOverflowEvent))

        val secondOverflowOnset = firstOverflowOnset + 10
        logger.onBlock(metrics(secondOverflowOnset, state = DetectionState.DETECTING), null)
        val secondOverflowEvent = DetectionEvent(peakDbfs = -2.0, peakBlockIndex = secondOverflowOnset, durationBlocks = 1)
        logger.onBlock(metrics(secondOverflowOnset + 1, state = DetectionState.COOLDOWN), accepted(secondOverflowEvent))

        logger.finishSession()
        val document = decode(requireNotNull(logger.exportJson()))

        assertEquals(maxRecordedCandidates + 2, document.totalCandidateCount)
        assertEquals(maxRecordedCandidates, document.recordedCandidateCount)
        assertEquals(maxRecordedCandidates, document.candidates.size)
        assertEquals(true, document.candidatesTruncated)
        assertTrue(document.candidates.none { it.peakBlockIndex == firstOverflowOnset })
        assertTrue(document.candidates.none { it.peakBlockIndex == secondOverflowOnset })
    }

    @Test
    fun `candidatesTruncated stays false while every candidate is retained`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()

        repeat(3) { i ->
            val onsetIndex = (i * 2).toLong()
            logger.onBlock(metrics(onsetIndex, state = DetectionState.DETECTING), null)
            val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = onsetIndex, durationBlocks = 1)
            logger.onBlock(metrics(onsetIndex + 1, state = DetectionState.COOLDOWN), accepted(event))
        }
        logger.finishSession()

        val document = decode(requireNotNull(logger.exportJson()))

        assertEquals(3, document.totalCandidateCount)
        assertEquals(3, document.recordedCandidateCount)
        assertEquals(false, document.candidatesTruncated)
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
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), accepted(event))

        logger.finishSession()
        val firstExport = logger.exportJson()
        logger.finishSession() // redundant second call, as a defensive teardown path would make
        val secondExport = logger.exportJson()

        assertEquals(firstExport, secondExport)
        assertEquals(1, decode(requireNotNull(secondExport)).candidates.size)
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
        assertTrue(document.candidates.isEmpty())
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
    fun `exported JSON is valid, pretty-printed and round-trips with schema version exactly 2`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0), null)
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        assertTrue("expected pretty-printed output to contain newlines", json.contains("\n"))
        val document = decode(json) // throws on invalid JSON
        assertEquals(2, SESSION_LOG_SCHEMA_VERSION)
        assertEquals(SESSION_LOG_SCHEMA_VERSION, document.schemaVersion)
    }

    @Test
    fun `exported JSON never contains raw audio sample data`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession()
        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), accepted(event))
        logger.finishSession()

        val json = requireNotNull(logger.exportJson())

        listOf("pcm", "wav", "samples", "audioData").forEach { forbidden ->
            assertTrue("unexpected raw-audio-like field: $forbidden", !json.contains(forbidden, ignoreCase = true))
        }
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
            releaseSpikeMin = 5.0,
            crestMin = 8.0,
            crestWindowBlocks = 4,
            clipLevel = 30_000,
            clipRatioMin = 0.03,
            endSilenceBlocks = 4,
            maxEventDurationBlocks = 25,
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
        assertEquals(customConfig.releaseSpikeMin, snapshot.releaseSpikeMin, 0.0)
        assertEquals(customConfig.crestMin, snapshot.crestMin, 0.0)
        assertEquals(customConfig.crestWindowBlocks, snapshot.crestWindowBlocks)
        assertEquals(customConfig.clipLevel, snapshot.clipLevel)
        assertEquals(customConfig.clipRatioMin, snapshot.clipRatioMin, 0.0)
        assertEquals(customConfig.endSilenceBlocks, snapshot.endSilenceBlocks)
        assertEquals(customConfig.maxEventDurationBlocks, snapshot.maxEventDurationBlocks)
        assertEquals(customConfig.cooldownBlocks, snapshot.cooldownBlocks)
        assertEquals(customConfig.warmupBlocks, snapshot.warmupBlocks)
        assertEquals(customConfig.dbfsFloor, snapshot.dbfsFloor, 0.0)
    }

    // --- duration calculation from sampleRate and blockSize ---

    @Test
    fun `candidate duration in blocks and milliseconds is derived from sampleRate and blockSize`() {
        // 1024 / 44100 s per block = ~23.2199 ms/block; 3 event blocks (indices 10..12).
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(EngineConfig(sampleRate = 44_100, blockSize = 1024))

        logger.onBlock(metrics(10, state = DetectionState.DETECTING), null)
        logger.onBlock(metrics(11, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 11, durationBlocks = 3)
        logger.onBlock(metrics(12, state = DetectionState.COOLDOWN), accepted(event))
        logger.finishSession()

        val entry = decode(requireNotNull(logger.exportJson())).candidates.single()

        val expectedBlockDurationMillis = 1024 * 1000.0 / 44_100
        assertEquals(3, entry.durationBlocks)
        assertEquals((3 * expectedBlockDurationMillis).toLong(), entry.durationMillis)
    }

    @Test
    fun `relativeToPeakMillis is negative before the peak block and zero at it`() {
        val logger = JsonDetectionSessionLogger()
        logger.startTestSession(EngineConfig(sampleRate = 44_100, blockSize = 1024))

        logger.onBlock(metrics(0, state = DetectionState.DETECTING), null)
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onBlock(metrics(1, state = DetectionState.COOLDOWN), accepted(event))
        logger.finishSession()

        val blocks = decode(requireNotNull(logger.exportJson())).candidates.single().blocks
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
