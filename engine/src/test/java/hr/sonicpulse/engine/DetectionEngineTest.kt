package hr.sonicpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class DetectionEngineTest {

    private val config = EngineConfig()

    private fun silenceBlock(size: Int = config.blockSize): ShortArray = ShortArray(size)

    private fun constantBlock(amplitude: Short, size: Int = config.blockSize): ShortArray =
        ShortArray(size) { amplitude }

    /**
     * A block that is mostly silent except for a short loud burst, tall enough to clear
     * DBFS_MIN/SPIKE_MIN and, combined with 2 quiet neighboring blocks in the crest window,
     * produce a high crest factor (a real impulse "shape").
     */
    private fun impulseBlock(size: Int = config.blockSize): ShortArray =
        ShortArray(size) { index -> if (index < 32) 20_000 else 0 }

    /** A sustained loud plateau: clips heavily, but its flat shape keeps crest low. */
    private fun clippedPlateauBlock(size: Int = config.blockSize): ShortArray =
        ShortArray(size) { index -> if (index < 600) 32_700 else 0 }

    /** Computed dBFS of a block containing [burstSize] samples of [amplitude] and silence
     * elsewhere in a [size]-sample block — the exact shape [impulseBlock] uses, exposed as a
     * reusable formula for tests that need to reason about dBFS in absolute terms rather than
     * relative to a baseline. */
    private fun burstDbfs(amplitude: Int, burstSize: Int, size: Int = config.blockSize): Double {
        val rms = amplitude * sqrt(burstSize.toDouble() / size)
        return 20.0 * log10(rms / 32768.0)
    }

    /** [impulseBlock]'s own dBFS level, in absolute terms — the typical onset/peak level used
     * throughout this file. */
    private val impulseDbfs = burstDbfs(amplitude = 20_000, burstSize = 32)

    /** A flat (constant-amplitude, crest ~0) block at a precisely controlled [dbfs] level. A
     * block this flat can never satisfy the strict onset trigger on its own regardless of how
     * loud [dbfs] is (crestMin requires a real impulse shape) — safe to use for release-only
     * scenarios without accidentally starting a *new* candidate. */
    private fun flatBlockAt(dbfs: Double, size: Int = config.blockSize): ShortArray {
        val amplitude = (32_768.0 * 10.0.pow(dbfs / 20.0)).toInt().coerceIn(1, 32_767)
        return ShortArray(size) { amplitude.toShort() }
    }

    /** A flat block [dropFromPeakDb] below [peakDbfs] — clears the peak-relative release
     * condition as long as [dropFromPeakDb] is less than [EngineConfig.releaseDropDb], while
     * (per [flatBlockAt]) never satisfying the full onset trigger on its own. */
    private fun releaseActiveFlatBlock(peakDbfs: Double, dropFromPeakDb: Double = 10.0): ShortArray =
        flatBlockAt(peakDbfs - dropFromPeakDb)

    private fun feedSilence(engine: DetectionEngine, count: Int) {
        repeat(count) { engine.process(silenceBlock()) }
    }

    @Test
    fun `synthetic silence never triggers a detection`() {
        val engine = DetectionEngine(config)

        val events = (0 until 200).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `constant loud noise never triggers because crest is too low`() {
        val engine = DetectionEngine(config)

        val events = (0 until 200).mapNotNull { engine.process(constantBlock(20_000)) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `a synthetic impulse after warmup triggers exactly one detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val impulseIndex = config.warmupBlocks.toLong()
        val duringImpulse = engine.process(impulseBlock())
        val silent1 = engine.process(silenceBlock())
        val silent2 = engine.process(silenceBlock())
        val silent3 = engine.process(silenceBlock())
        val after = (0 until 20).mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringImpulse)
        assertNull(silent1)
        assertNull(silent2)
        assertNotNull(silent3)
        assertEquals(impulseIndex, silent3!!.peakBlockIndex)
        assertEquals(emptyList<DetectionEvent>(), after)
    }

    @Test
    fun `baseline is frozen while in the DETECTING state`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val baselineAtDetectionStart = engine.currentBaseline

        // Falls back to silence but stays in DETECTING (only 1 of 3 end-silence blocks so far).
        engine.process(silenceBlock())

        assertEquals(baselineAtDetectionStart, engine.currentBaseline, 0.0)
    }

    @Test
    fun `a clipped impulse triggers via the clipping branch despite low crest`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(clippedPlateauBlock())
        val events = (0 until 3).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
    }

    @Test
    fun `an impulse train within cooldown produces only one event`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val events = mutableListOf<DetectionEvent>()
        events += listOfNotNull(engine.process(impulseBlock()))
        events += listOfNotNull(engine.process(silenceBlock()))
        events += listOfNotNull(engine.process(silenceBlock()))
        events += listOfNotNull(engine.process(silenceBlock())) // ends DETECTING, emits 1 event

        // Second impulse arrives immediately, while still inside the cooldown window.
        events += listOfNotNull(engine.process(impulseBlock()))
        repeat(config.cooldownBlocks) {
            events += listOfNotNull(engine.process(silenceBlock()))
        }

        assertEquals(1, events.size)
    }

    @Test
    fun `a new impulse after cooldown ends triggers a second, independent detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val firstEvents = mutableListOf<DetectionEvent>()
        firstEvents += listOfNotNull(engine.process(impulseBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        repeat(config.cooldownBlocks) { engine.process(silenceBlock()) }

        val secondEvents = mutableListOf<DetectionEvent>()
        secondEvents += listOfNotNull(engine.process(impulseBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))

        assertEquals(1, firstEvents.size)
        assertEquals(1, secondEvents.size)
    }

    @Test
    fun `process rejects an empty block`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(0))
        }
    }

    @Test
    fun `process rejects a block smaller than the configured block size`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(config.blockSize - 1))
        }
    }

    @Test
    fun `process rejects a block larger than the configured block size`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(config.blockSize + 1))
        }
    }

    @Test
    fun `process accepts a correctly sized block without throwing`() {
        val engine = DetectionEngine(config)

        engine.process(silenceBlock())
    }

    @Test
    fun `a rejected block does not seed or mutate the baseline`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(0))
        }
        assertEquals(0.0, engine.currentBaseline, 0.0)

        engine.process(silenceBlock())
        assertEquals(config.dbfsFloor, engine.currentBaseline, 0.0)
    }

    @Test
    fun `rejected blocks do not advance the processed block index used for warmup gating`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks - 1)

        repeat(5) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.process(ShortArray(config.blockSize - 1))
            }
        }

        val events = mutableListOf<DetectionEvent>()
        events += listOfNotNull(engine.process(impulseBlock()))
        events += (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `spike is computed against the baseline as it stood before this block, not after`() {
        val burstSize = 32
        val seedAmplitude = 1000

        fun burstBlock(amplitude: Int): ShortArray =
            ShortArray(config.blockSize) { index -> if (index < burstSize) amplitude.toShort() else 0 }

        fun dbfsOf(rms: Double): Double = 20.0 * log10(rms / 32768.0)

        val seedDbfs = dbfsOf(seedAmplitude.toDouble())

        // A target spike that, computed against the OLD (pre-update) baseline, clears
        // spikeMin — but that the buggy ordering (this block's own dBFS shrinking the
        // spike via an alphaUp-sized baseline nudge first) would pull back under spikeMin.
        // Midpoint of the window (spikeMin, spikeMin / (1 - alphaUp)) where exactly this happens.
        val targetSpike = config.spikeMin / (1 - config.alphaUp / 2)
        val buggySpike = targetSpike * (1 - config.alphaUp)
        check(buggySpike < config.spikeMin) {
            "test setup invalid: buggySpike=$buggySpike must fall under spikeMin=${config.spikeMin}"
        }

        val impulseDbfs = seedDbfs + targetSpike
        check(impulseDbfs > config.dbfsMin) {
            "test setup invalid: impulseDbfs=$impulseDbfs must clear dbfsMin=${config.dbfsMin}"
        }
        val burstRms = 32768.0 * 10.0.pow(impulseDbfs / 20.0)
        val impulseAmplitude = (burstRms * sqrt(config.blockSize.toDouble() / burstSize)).toInt()
        check(impulseAmplitude in 1..32_767) {
            "test setup invalid: impulseAmplitude=$impulseAmplitude out of Short range"
        }

        val engine = DetectionEngine(config)
        repeat(config.warmupBlocks) {
            engine.process(ShortArray(config.blockSize) { seedAmplitude.toShort() })
        }
        check(engine.currentBaseline == seedDbfs) {
            "test setup invalid: baseline did not converge to seedDbfs, was ${engine.currentBaseline}"
        }

        engine.process(burstBlock(impulseAmplitude))
        // Closes with silence, not another seedAmplitude ("ambient") block: under the
        // peak-relative release condition, a block only ~15 dB below the onset peak (as
        // seedAmplitude blocks are here) still counts as release-active, so silence —
        // dramatically below any realistic peak — is what genuinely closes the candidate.
        val closingEvents = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, closingEvents.size)
    }

    @Test
    fun `a later louder release-active block becomes the event peak`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        fun burstDbfs(amplitude: Int, burstSize: Int): Double {
            val rms = amplitude * sqrt(burstSize.toDouble() / config.blockSize)
            return 20.0 * log10(rms / 32768.0)
        }

        val impulseIndex = config.warmupBlocks.toLong()
        val impulseDbfs = burstDbfs(amplitude = 20_000, burstSize = 32) // matches impulseBlock()'s shape

        // A loud, sustained plateau: louder overall than the impulse, but its flat shape
        // (crest ~0) fails the full onset trigger, so it must not itself start a new event —
        // it should only extend the peak of the one already open.
        val plateauAmplitude = 25_000
        val plateauDbfs = 20.0 * log10(plateauAmplitude / 32768.0)
        check(plateauDbfs > impulseDbfs) {
            "test setup invalid: plateau ($plateauDbfs dBFS) must be louder than the impulse ($impulseDbfs dBFS)"
        }

        fun loudPlateauBlock(): ShortArray = ShortArray(config.blockSize) { plateauAmplitude.toShort() }

        engine.process(impulseBlock())
        val plateauIndex = impulseIndex + 1
        engine.process(loudPlateauBlock())

        // The plateau is within releaseDropDb of the (old) peak — in fact louder than it — so it
        // is release-active and resets the inactive counter to zero; closing the event now needs
        // the *full* endSilenceBlocks count of trailing inactive blocks, not one fewer as it
        // would if the plateau had merely been ignored.
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
        assertEquals(plateauDbfs, events.single().peakDbfs, 1e-9)
        assertEquals(plateauIndex, events.single().peakBlockIndex)
    }

    @Test
    fun `the last warmup block still suppresses triggering`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks - 1)

        val duringLastWarmupBlock = engine.process(impulseBlock())
        val followUp = (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringLastWarmupBlock)
        assertEquals(emptyList<DetectionEvent>(), followUp)
    }

    @Test
    fun `the first post-warmup block is eligible to trigger`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
    }

    @Test
    fun `one inactive block does not end an open detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val result = engine.process(silenceBlock())

        assertNull(result)
    }

    @Test
    fun `endSilenceBlocks minus one consecutive inactive blocks do not end an open detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val results = (0 until config.endSilenceBlocks - 1).map { engine.process(silenceBlock()) }

        assertEquals(List(config.endSilenceBlocks - 1) { null }, results)
    }

    @Test
    fun `exactly endSilenceBlocks consecutive inactive blocks end the detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val results = (0 until config.endSilenceBlocks).map { engine.process(silenceBlock()) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `a release-active block before reaching endSilenceBlocks resets the inactive counter`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        engine.process(silenceBlock()) // 1 inactive block toward the count
        engine.process(impulseBlock()) // release-active block mid-DETECTING, resets the inactive counter to 0

        val stillOpen = engine.process(silenceBlock()) // this is only the 1st inactive block since the reset
        val closing = (0 until config.endSilenceBlocks - 1).mapNotNull { engine.process(silenceBlock()) }

        assertNull(stillOpen)
        assertEquals(1, closing.size)
    }

    @Test
    fun `an impulse during COOLDOWN is ignored and does not start a new event`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN

        val duringCooldown = engine.process(impulseBlock())
        val remainder = (0 until config.cooldownBlocks + config.endSilenceBlocks + 5)
            .mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringCooldown)
        assertEquals(emptyList<DetectionEvent>(), remainder)
    }

    @Test
    fun `cooldown completes after exactly cooldownBlocks and the final block is not re-evaluated as IDLE`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN
        repeat(config.cooldownBlocks - 1) { engine.process(silenceBlock()) } // one block short of IDLE

        // This block is the cooldownBlocks-th cooldown block: state flips to IDLE by the end of
        // processing it, but the block itself was dispatched while still COOLDOWN and must not
        // be retroactively evaluated as a fresh IDLE trigger, despite being a loud impulse.
        val finalCooldownBlock = engine.process(impulseBlock())
        val afterCooldown = (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertNull(finalCooldownBlock)
        assertEquals(emptyList<DetectionEvent>(), afterCooldown)
    }

    @Test
    fun `baseline is frozen while in the COOLDOWN state`() {
        val ambientAmplitude: Short = 50
        fun ambientBlock(): ShortArray = ShortArray(config.blockSize) { ambientAmplitude }

        val engine = DetectionEngine(config)
        repeat(config.warmupBlocks) { engine.process(ambientBlock()) }
        val baselineBeforeEvent = engine.currentBaseline

        engine.process(impulseBlock()) // far louder than ambient; triggers and opens DETECTING
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN

        // Silence is much quieter than the ambient baseline; if COOLDOWN did not freeze the
        // baseline, this block would pull it toward the floor via alphaDown.
        engine.process(silenceBlock())

        assertEquals(baselineBeforeEvent, engine.currentBaseline, 0.0)
    }

    @Test
    fun `a single impulse produces exactly one DetectionEvent, not zero or multiple`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val events = mutableListOf<DetectionEvent?>()
        events += engine.process(impulseBlock())
        events += (0 until config.endSilenceBlocks + 10).map { engine.process(silenceBlock()) }

        assertEquals(1, events.count { it != null })
    }

    @Test
    fun `cooldownBlocks of zero skips COOLDOWN entirely, allowing the very next block to start a new event`() {
        val zeroCooldownConfig = EngineConfig(cooldownBlocks = 0)
        val engine = DetectionEngine(zeroCooldownConfig)
        feedSilence(engine, zeroCooldownConfig.warmupBlocks)

        val firstEvents = mutableListOf<DetectionEvent>()
        firstEvents += listOfNotNull(engine.process(impulseBlock()))
        firstEvents += (0 until zeroCooldownConfig.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        // No blocks should be ignored: the impulse immediately following the first event's
        // close must itself start a second, independent detection.
        val secondEvents = mutableListOf<DetectionEvent>()
        secondEvents += listOfNotNull(engine.process(impulseBlock()))
        secondEvents += (0 until zeroCooldownConfig.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, firstEvents.size)
        assertEquals(1, secondEvents.size)
    }

    @Test
    fun `lastBlockMetrics is null before any block has been processed`() {
        val engine = DetectionEngine(config)

        assertNull(engine.lastBlockMetrics)
    }

    @Test
    fun `lastBlockMetrics reflects dbfs, rms, clipRatio and a null crest for a silence block in IDLE`() {
        val engine = DetectionEngine(config)

        engine.process(silenceBlock())

        val metrics = engine.lastBlockMetrics
        assertNotNull(metrics)
        assertEquals(0.0, metrics!!.rms, 0.0)
        assertEquals(config.dbfsFloor, metrics.dbfs, 0.0)
        assertEquals(0.0, metrics.clipRatio, 0.0)
        assertNull(metrics.crest)
        assertEquals(DetectionState.IDLE, metrics.state)
    }

    @Test
    fun `lastBlockMetrics blockIndex matches the number of blocks already processed`() {
        val engine = DetectionEngine(config)

        engine.process(silenceBlock())
        engine.process(silenceBlock())
        engine.process(silenceBlock())

        assertEquals(2L, engine.lastBlockMetrics!!.blockIndex)
    }

    @Test
    fun `lastBlockMetrics baseline is the pre-update value used for this block's spike, not the value after`() {
        val engine = DetectionEngine(config)
        val baselineBeforeFirstCall = engine.currentBaseline

        engine.process(silenceBlock())

        val baselineAfterFirstCall = engine.currentBaseline
        check(baselineBeforeFirstCall != baselineAfterFirstCall) {
            "test setup invalid: baseline did not change on the first update, " +
                "was $baselineBeforeFirstCall before and after"
        }
        assertEquals(baselineBeforeFirstCall, engine.lastBlockMetrics!!.baseline, 0.0)
    }

    @Test
    fun `lastBlockMetrics reports DETECTING right after a triggering impulse block`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())

        assertEquals(DetectionState.DETECTING, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `lastBlockMetrics reports COOLDOWN on the block that closes a detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) }

        assertEquals(DetectionState.COOLDOWN, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `lastBlockMetrics reports IDLE on the closing block when cooldownBlocks is zero`() {
        val zeroCooldownConfig = EngineConfig(cooldownBlocks = 0)
        val engine = DetectionEngine(zeroCooldownConfig)
        feedSilence(engine, zeroCooldownConfig.warmupBlocks)

        engine.process(impulseBlock())
        repeat(zeroCooldownConfig.endSilenceBlocks) { engine.process(silenceBlock()) }

        assertEquals(DetectionState.IDLE, engine.lastBlockMetrics!!.state)
    }

    // --- release-active semantics, event duration and candidate completions ---

    @Test
    fun `a release-active block that fails onset keeps the event open and resets the inactive counter`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // onset -> DETECTING, peak = impulseDbfs
        engine.process(silenceBlock()) // 1st of endSilenceBlocks inactive confirmation blocks

        val stillOpen = engine.process(releaseActiveFlatBlock(peakDbfs = impulseDbfs))
        assertNull(stillOpen) // fails the full onset trigger, but stays active — event remains open

        // If the inactive counter had not been reset by the release-active block above, only
        // 2 more inactive blocks (endSilenceBlocks - 1) would be needed to close it. Supplying
        // exactly that many must NOT close the event...
        val notYetClosed = (0 until config.endSilenceBlocks - 1).mapNotNull { engine.process(silenceBlock()) }
        assertEquals(emptyList<DetectionEvent>(), notYetClosed)

        // ...the full endSilenceBlocks count, counted fresh from the reset, is required.
        val closing = engine.process(silenceBlock())
        assertNotNull(closing)
    }

    // --- peak-relative release (releaseDropDb) ---

    @Test
    fun `a candidate closes after exactly endSilenceBlocks blocks more than releaseDropDb below its peak`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // onset, peak = impulseDbfs
        val belowRelease = flatBlockAt(impulseDbfs - config.releaseDropDb - 5.0) // comfortably beyond the drop
        val results = (0 until config.endSilenceBlocks).map { engine.process(belowRelease) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `a very low frozen baseline does not keep a completed short impulse active`() {
        // Reproduces the reported bug directly: a background baseline pinned near dbfsFloor
        // (silence during warmup), followed by an impulse, then blocks at a realistic room-noise
        // level that is comfortably above the OLD baseline-relative releaseSpikeMin threshold —
        // spike here would be roughly 60 relative to the frozen -120 dBFS baseline, far above the
        // old 6.0 threshold — but well more than releaseDropDb below the candidate's own peak.
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)
        check(engine.currentBaseline == config.dbfsFloor)

        engine.process(impulseBlock()) // onset, peak = impulseDbfs (~-19.3 dBFS)
        val roomNoise = flatBlockAt(-60.0) // far louder than the frozen baseline, far quieter than the peak
        val results = (0 until config.endSilenceBlocks).mapNotNull { engine.process(roomNoise) }

        assertEquals(1, results.size)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }

    @Test
    fun `a block at or just below the release boundary is inactive`() {
        // flatBlockAt() converts a requested dBFS value into an integer PCM amplitude
        // (truncating toward zero), so the block it generates lands at or slightly below the
        // requested level, never above it — this proves the boundary is not still-active, not
        // that the comparison is exact to the floating-point boundary.
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // onset, peak = impulseDbfs
        val atOrBelowBoundary = flatBlockAt(impulseDbfs - config.releaseDropDb)
        val results = (0 until config.endSilenceBlocks).map { engine.process(atOrBelowBoundary) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `a sustained signal that stays within releaseDropDb of its peak still reaches the hard duration limit and is rejected TOO_LONG`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // block 1 of the span, peak = impulseDbfs
        val sustainedNearPeak = flatBlockAt(impulseDbfs - 5.0) // within releaseDropDb (20.0) of the peak throughout
        val upToCap = (1 until config.maxEventDurationBlocks).map { engine.process(sustainedNearPeak) } // blocks 2..30
        assertTrue(upToCap.all { it == null })
        assertNull(engine.lastCandidateCompletion)

        val result = engine.process(sustainedNearPeak) // block 31: duration 31 -> rejected

        assertNull(result)
        val rejected = engine.lastCandidateCompletion
        assertTrue(rejected is CandidateCompletion.Rejected)
        rejected as CandidateCompletion.Rejected
        assertEquals(CandidateRejectionReason.TOO_LONG, rejected.reason)
        assertEquals(config.maxEventDurationBlocks + 1, rejected.durationBlocks)
    }

    @Test
    fun `durationBlocks excludes the trailing inactive confirmation blocks`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // the only active block of this event
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
        assertEquals(1, events.single().durationBlocks)
    }

    @Test
    fun `an internal inactive gap shorter than endSilenceBlocks is included in the duration span`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // onset: block 1 of the span
        engine.process(impulseBlock()) // active: block 2
        engine.process(silenceBlock()) // internal gap, 1 of 2 — strictly shorter than endSilenceBlocks (3)
        engine.process(silenceBlock()) // internal gap, 2 of 2 — still short of endSilenceBlocks
        engine.process(impulseBlock()) // active again: block 5 (the gap did not close the event)

        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
        // 5 blocks span (onset through the last active block), even though 2 of them were the
        // internal inactive gap.
        assertEquals(5, events.single().durationBlocks)
    }

    @Test
    fun `an internal inactive gap can push a candidate's span past maxEventDurationBlocks, rejecting it as TOO_LONG`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // onset: block 1 of the span
        engine.process(silenceBlock()) // internal gap, 1 of 2 — strictly shorter than endSilenceBlocks (3)
        engine.process(silenceBlock()) // internal gap, 2 of 2 — still short of endSilenceBlocks; event stays open

        // Blocks 3..29 of the span: all active, none individually checked past the gap. Only 27
        // of these calls are active blocks, yet the span (which also counts the 2 gap blocks
        // above) reaches exactly maxEventDurationBlocks (30) here — not rejected.
        val upToCap = (3 until config.maxEventDurationBlocks).map { engine.process(impulseBlock()) }
        assertTrue(upToCap.all { it == null })
        assertNull(engine.lastCandidateCompletion)

        // The next active block extends the span to maxEventDurationBlocks + 1 (31) — even though
        // the internal gap means only 28 blocks so far were ever individually active. This is the
        // block that gets rejected immediately.
        val result = engine.process(impulseBlock())

        assertNull(result)
        val rejected = engine.lastCandidateCompletion
        assertTrue(rejected is CandidateCompletion.Rejected)
        rejected as CandidateCompletion.Rejected
        assertEquals(CandidateRejectionReason.TOO_LONG, rejected.reason)
        assertEquals(config.maxEventDurationBlocks + 1, rejected.durationBlocks)

        // The engine must have left DETECTING (default rejectedCooldownBlocks > 0 -> COOLDOWN).
        assertEquals(DetectionState.COOLDOWN, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `a candidate spanning exactly maxEventDurationBlocks is never rejected`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // block 1 of the span
        val results = (1 until config.maxEventDurationBlocks).map { engine.process(impulseBlock()) } // blocks 2..30

        assertTrue(results.all { it == null })
        assertNull(engine.lastCandidateCompletion)
    }

    @Test
    fun `a candidate with exactly the maximum duration finishes normally once the required inactive blocks follow`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // block 1
        repeat(config.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) } // blocks 2..30
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
        assertEquals(config.maxEventDurationBlocks, events.single().durationBlocks)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }

    @Test
    fun `CandidateCompletion Accepted contains the exact DetectionEvent returned by process()`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val event = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }.single()

        val completion = engine.lastCandidateCompletion
        assertTrue(completion is CandidateCompletion.Accepted)
        assertEquals(event, (completion as CandidateCompletion.Accepted).event)
    }

    @Test
    fun `an active block that pushes duration past maxEventDurationBlocks is rejected immediately`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock()) // block 1
        repeat(config.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) } // blocks 2..30, duration 30: not rejected
        val result = engine.process(impulseBlock()) // block 31: duration 31 -> rejected

        assertNull(result)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Rejected)
    }

    @Test
    fun `a rejected TOO_LONG candidate reports the reason, the tracked peak and the duration at rejection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val onsetIndex = config.warmupBlocks.toLong()
        val peakIndex = onsetIndex + 1
        val plateauDbfs = 20.0 * log10((32_700.0 * sqrt(600.0 / config.blockSize)) / 32768.0)

        engine.process(impulseBlock())          // onset: block 1 of the span
        engine.process(clippedPlateauBlock())   // active, louder: becomes the tracked peak (block 2)
        // blocks 3..30: still active, all quieter than the plateau, so the peak does not move.
        repeat(config.maxEventDurationBlocks - 2) { engine.process(impulseBlock()) }
        val result = engine.process(impulseBlock()) // block 31: duration 31 -> rejected

        assertNull(result) // process() returns null for a rejected candidate

        val rejected = engine.lastCandidateCompletion
        assertTrue(rejected is CandidateCompletion.Rejected)
        rejected as CandidateCompletion.Rejected
        assertEquals(CandidateRejectionReason.TOO_LONG, rejected.reason)
        assertEquals(plateauDbfs, rejected.peakDbfs, 1e-6)
        assertEquals(peakIndex, rejected.peakBlockIndex)
        assertEquals(31, rejected.durationBlocks)

        // The engine must have left DETECTING (default rejectedCooldownBlocks > 0 -> COOLDOWN).
        assertEquals(DetectionState.COOLDOWN, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `a new impulse is accepted normally after a rejected candidate completes its cooldown`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) } // reach duration 30
        val rejectingResult = engine.process(impulseBlock()) // duration 31 -> rejected, enters COOLDOWN
        check(rejectingResult == null)
        check(engine.lastCandidateCompletion is CandidateCompletion.Rejected)

        // More than enough silence to exhaust rejectedCooldownBlocks (the rejected path's actual
        // cooldown length) and reach IDLE well before this many blocks pass.
        repeat(config.cooldownBlocks) { engine.process(silenceBlock()) }

        val secondOnset = engine.process(impulseBlock())
        val secondEvents = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertNull(secondOnset)
        assertEquals(1, secondEvents.size)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }

    @Test
    fun `a rejected candidate uses rejectedCooldownBlocks, not the full cooldownBlocks`() {
        val shortRejectedCooldownConfig = EngineConfig(cooldownBlocks = 30, rejectedCooldownBlocks = 5)
        val engine = DetectionEngine(shortRejectedCooldownConfig)
        feedSilence(engine, shortRejectedCooldownConfig.warmupBlocks)

        engine.process(impulseBlock())
        repeat(shortRejectedCooldownConfig.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) }
        val rejectingResult = engine.process(impulseBlock()) // duration 31 -> rejected, enters COOLDOWN
        check(rejectingResult == null)
        check(engine.lastCandidateCompletion is CandidateCompletion.Rejected)

        // rejectedCooldownBlocks - 1 processed cooldown blocks: still COOLDOWN, not yet IDLE.
        repeat(shortRejectedCooldownConfig.rejectedCooldownBlocks - 1) { engine.process(silenceBlock()) }
        assertEquals(DetectionState.COOLDOWN, engine.lastBlockMetrics!!.state)

        // The rejectedCooldownBlocks-th cooldown block: state flips to IDLE, well short of the
        // much longer cooldownBlocks (30) an accepted detection would have required.
        engine.process(silenceBlock())
        assertEquals(DetectionState.IDLE, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `an accepted detection still uses the full cooldownBlocks, unaffected by rejectedCooldownBlocks`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }
        check(events.size == 1)
        check(engine.lastCandidateCompletion is CandidateCompletion.Accepted)

        // config.rejectedCooldownBlocks (5) worth of silence is nowhere near enough to exit
        // COOLDOWN for an accepted detection — only cooldownBlocks (30) governs this path.
        repeat(config.rejectedCooldownBlocks) { engine.process(silenceBlock()) }
        assertEquals(DetectionState.COOLDOWN, engine.lastBlockMetrics!!.state)

        repeat(config.cooldownBlocks - config.rejectedCooldownBlocks) { engine.process(silenceBlock()) }
        assertEquals(DetectionState.IDLE, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `a rejected candidate transitions directly to IDLE when rejectedCooldownBlocks is zero`() {
        val zeroRejectedCooldownConfig = EngineConfig(rejectedCooldownBlocks = 0)
        val engine = DetectionEngine(zeroRejectedCooldownConfig)
        feedSilence(engine, zeroRejectedCooldownConfig.warmupBlocks)

        engine.process(impulseBlock())
        repeat(zeroRejectedCooldownConfig.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) }
        val result = engine.process(impulseBlock()) // duration 31 -> rejected

        assertNull(result)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Rejected)
        assertEquals(DetectionState.IDLE, engine.lastBlockMetrics!!.state)
    }

    @Test
    fun `a new impulse can be detected on the first processable block after rejectedCooldownBlocks, well before the previous shared cooldownBlocks would have ended`() {
        val shortRejectedCooldownConfig = EngineConfig(cooldownBlocks = 30, rejectedCooldownBlocks = 5)
        val engine = DetectionEngine(shortRejectedCooldownConfig)
        feedSilence(engine, shortRejectedCooldownConfig.warmupBlocks)

        engine.process(impulseBlock())
        repeat(shortRejectedCooldownConfig.maxEventDurationBlocks - 1) { engine.process(impulseBlock()) }
        val rejectingResult = engine.process(impulseBlock()) // duration 31 -> rejected, enters COOLDOWN
        check(rejectingResult == null)
        check(engine.lastCandidateCompletion is CandidateCompletion.Rejected)

        // rejectedCooldownBlocks (5) blocks of cooldown, then the very next block (the 1st
        // processable block after rejectedCooldownBlocks, i.e. rejectedCooldownBlocks + 1 blocks
        // after the rejection) is a genuine new impulse.
        repeat(shortRejectedCooldownConfig.rejectedCooldownBlocks) { engine.process(silenceBlock()) }
        check(DetectionState.IDLE == engine.lastBlockMetrics!!.state)

        val secondOnset = engine.process(impulseBlock())
        val secondEvents =
            (0 until shortRejectedCooldownConfig.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertNull(secondOnset)
        assertEquals(1, secondEvents.size)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)

        // Under the previous shared 30-block cooldown behavior, only rejectedCooldownBlocks + 1
        // (6) blocks would have passed since the rejection — still well inside a 30-block
        // cooldown, so this same impulse would still have been ignored.
        assertTrue(shortRejectedCooldownConfig.rejectedCooldownBlocks + 1 < shortRejectedCooldownConfig.cooldownBlocks)
    }

    @Test
    fun `lastCandidateCompletion resets to null on the next ordinary block after a completion`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }
        check(events.size == 1)
        check(engine.lastCandidateCompletion is CandidateCompletion.Accepted)

        engine.process(silenceBlock()) // an ordinary block, well into COOLDOWN

        assertNull(engine.lastCandidateCompletion)
    }

    @Test
    fun `lastCandidateCompletion stays null on an ordinary block that neither opens nor closes an event`() {
        val engine = DetectionEngine(config)

        engine.process(silenceBlock())

        assertNull(engine.lastCandidateCompletion)
    }

    // --- onset-baseline floor on the release threshold (weak impulse above a raised ambient) ---

    /** Seeds the engine's baseline to exactly [dbfs] and holds it there: [BaselineTracker] sets
     * its value directly (no EMA smoothing) on the very first update it ever receives, and a flat
     * block repeating that same value leaves it unchanged on every update after. Also advances
     * the engine past [EngineConfig.warmupBlocks] so a subsequent onset block is eligible to
     * trigger. */
    private fun seedBaseline(engine: DetectionEngine, dbfs: Double) {
        repeat(config.warmupBlocks) { engine.process(flatBlockAt(dbfs)) }
    }

    @Test
    fun `a weak impulse above a raised ambient baseline closes on returning to that baseline, not stalling on the peak-relative drop alone`() {
        val engine = DetectionEngine(config)
        seedBaseline(engine, -36.0)
        // flatBlockAt() truncates its PCM amplitude toward zero, so the baseline this seeds is
        // at or fractionally below -36.0, not exactly equal to it — compare with tolerance.
        val baselineDbfs = engine.currentBaseline
        assertEquals(-36.0, baselineDbfs, 0.1)

        val onsetResult = engine.process(impulseBlock())
        assertNull(onsetResult)
        val onsetMetrics = engine.lastBlockMetrics!!
        // Confirms this block actually produces the approximate onset described in the reported
        // scenario before relying on it: ~-19 dBFS peak, ~17 dB spike over the -36 dBFS baseline,
        // both clearing dbfsMin/spikeMin. Under the pre-fix peak-relative-only rule, the release
        // threshold here would be peakDbfs - releaseDropDb = -19.3 - 20 = -39.3 dBFS — below the
        // -36 dBFS the signal actually returns to, so it would never close on that return alone.
        assertEquals(-19.34, onsetMetrics.dbfs, 0.1)
        assertEquals(16.66, onsetMetrics.spike, 0.1)
        assertTrue(onsetMetrics.dbfs > config.dbfsMin)
        assertTrue(onsetMetrics.spike > config.spikeMin)
        check(onsetMetrics.dbfs - config.releaseDropDb < baselineDbfs) {
            "test setup invalid: peak-relative threshold must sit below the onset baseline " +
                "for this scenario to exercise the floor"
        }

        // Returns to the exact onset baseline.
        val results = (0 until config.endSilenceBlocks).map { engine.process(flatBlockAt(baselineDbfs)) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        val closing = results.last()
        assertNotNull(closing)
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }

    @Test
    fun `a strong impulse still uses the peak-relative threshold when it sits above the onset baseline`() {
        val engine = DetectionEngine(config)
        seedBaseline(engine, -36.0)

        engine.process(clippedPlateauBlock()) // loud onset via the clipping branch; peak well above dbfsMin
        val peakDbfs = engine.lastBlockMetrics!!.dbfs
        check(peakDbfs - config.releaseDropDb > -36.0) {
            "test setup invalid: peak-relative threshold (${peakDbfs - config.releaseDropDb}) " +
                "must sit above the onset baseline (-36.0) for this scenario"
        }

        // Strictly between the (higher) peak-relative threshold and the (lower) onset baseline.
        // If the baseline wrongly won out over the peak-relative threshold, this block would
        // count as active and the candidate would never close within endSilenceBlocks.
        val betweenThresholds = (peakDbfs - config.releaseDropDb + -36.0) / 2.0
        val results = (0 until config.endSilenceBlocks).map { engine.process(flatBlockAt(betweenThresholds)) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }

    @Test
    fun `a later louder peak raises the peak-relative release threshold above the frozen onset baseline`() {
        val engine = DetectionEngine(config)
        seedBaseline(engine, -36.0)

        engine.process(impulseBlock()) // onset, peak ~ -19.3 dBFS
        engine.process(clippedPlateauBlock()) // release-active, louder: becomes the new peak
        val newPeakDbfs = engine.lastBlockMetrics!!.dbfs
        check(newPeakDbfs - config.releaseDropDb > -36.0) {
            "test setup invalid: the new peak-relative threshold must exceed the onset baseline"
        }

        // Below the raised peak-relative threshold but above the onset baseline: only correct if
        // the release threshold tracks the updated peak rather than falling back to the frozen
        // baseline (which this level would also clear, masking a stale-peak bug).
        val belowNewThreshold = newPeakDbfs - config.releaseDropDb - 1.0
        check(belowNewThreshold > -36.0)
        val results = (0 until config.endSilenceBlocks).map { engine.process(flatBlockAt(belowNewThreshold)) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `a new candidate captures its own onset baseline, not the previous candidate's`() {
        val zeroCooldownConfig = EngineConfig(cooldownBlocks = 0)
        val engine = DetectionEngine(zeroCooldownConfig)
        seedBaseline(engine, -36.0)
        val firstBaselineDbfs = engine.currentBaseline

        // First candidate: opens and closes against firstBaselineDbfs, immediately back to IDLE
        // (cooldownBlocks = 0).
        engine.process(impulseBlock())
        val firstEvents = (0 until zeroCooldownConfig.endSilenceBlocks)
            .mapNotNull { engine.process(flatBlockAt(firstBaselineDbfs)) }
        check(firstEvents.size == 1)

        // Ambient rises well above the first candidate's baseline. Enough repeats for the
        // (unsmoothed-on-first-touch, then EMA) baseline to settle arbitrarily close to -20.0.
        repeat(200) { engine.process(flatBlockAt(-20.0)) }
        val secondBaselineDbfs = engine.currentBaseline
        check(secondBaselineDbfs > firstBaselineDbfs + 10.0) {
            "test setup invalid: baseline did not rise enough above the first candidate's, " +
                "was $secondBaselineDbfs vs $firstBaselineDbfs"
        }

        // Second candidate opens via the clipping branch, so it clears spikeMin regardless of how
        // close the raised baseline now sits to its peak (a plain impulseBlock() here could lose
        // spikeMin against a baseline this high and never trigger at all).
        engine.process(clippedPlateauBlock())
        val secondPeakDbfs = engine.lastBlockMetrics!!.dbfs

        // Two hypotheses for the release threshold: correct (this candidate's own, freshly
        // captured baseline) vs. buggy (a leaked baseline from the first candidate). Pick a level
        // strictly between them so only the correct one closes the candidate within
        // endSilenceBlocks.
        val leakedThreshold = maxOf(secondPeakDbfs - zeroCooldownConfig.releaseDropDb, firstBaselineDbfs)
        val correctThreshold = maxOf(secondPeakDbfs - zeroCooldownConfig.releaseDropDb, secondBaselineDbfs)
        check(correctThreshold > leakedThreshold + 0.5) {
            "test setup invalid: correct threshold ($correctThreshold) does not exceed the " +
                "leaked one ($leakedThreshold) enough to discriminate the bug"
        }
        val discriminatingLevel = (correctThreshold + leakedThreshold) / 2.0

        val results = (0 until zeroCooldownConfig.endSilenceBlocks)
            .map { engine.process(flatBlockAt(discriminatingLevel)) }

        assertEquals(zeroCooldownConfig.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `an invalid block does not clear a completion set by the previous valid block`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }
        check(events.size == 1)
        check(engine.lastCandidateCompletion is CandidateCompletion.Accepted)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(config.blockSize - 1))
        }

        assertTrue(engine.lastCandidateCompletion is CandidateCompletion.Accepted)
    }
}
