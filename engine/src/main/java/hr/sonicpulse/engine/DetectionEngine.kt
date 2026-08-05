package hr.sonicpulse.engine

import hr.sonicpulse.engine.metrics.AudioLevelCalculator
import hr.sonicpulse.engine.metrics.BaselineTracker
import hr.sonicpulse.engine.metrics.ClippingCalculator
import hr.sonicpulse.engine.metrics.CrestFactorTracker
import hr.sonicpulse.engine.metrics.SpikeCalculator
import hr.sonicpulse.engine.metrics.TriggerEvaluator

class DetectionEngine(private val config: EngineConfig = EngineConfig()) {

    private data class BlockSignal(
        val rms: Double,
        val dbfs: Double,
        val clipRatio: Double,
        val crest: Double?,
        val spike: Double
    )

    private var state = DetectionState.IDLE
    private var processedBlockIndex = 0L
    private var consecutiveInactiveBlocks = 0
    private var cooldownBlockCount = 0
    private var eventPeakDbfs = Double.NEGATIVE_INFINITY
    private var eventPeakBlockIndex = 0L
    private var eventStartBlockIndex = 0L
    private var lastActiveBlockIndex = 0L

    private val baseline = BaselineTracker(config.alphaDown, config.alphaUp)
    private val crestTracker = CrestFactorTracker(config.crestWindowBlocks)

    val currentBaseline: Double get() = baseline.value

    var lastBlockMetrics: BlockMetrics? = null
        private set

    /** The outcome of whichever candidate event left `DETECTING` on the most recently processed
     * block — null on every other block, including ordinary blocks that never opened or closed
     * an event. See [CandidateCompletion]. */
    var lastCandidateCompletion: CandidateCompletion? = null
        private set

    fun process(block: ShortArray): DetectionEvent? {
        require(block.size == config.blockSize) {
            "Block size must be ${config.blockSize}, was ${block.size}."
        }

        // Invalid-size input throws at the require() above, before this reset ever runs — so an
        // invalid block leaves whatever the previous valid block set here untouched.
        lastCandidateCompletion = null

        val blockIndex = processedBlockIndex
        processedBlockIndex++

        val stateAtEntry = state
        val signal = analyzeBlock(block)
        val baselineUsedForSpike = baseline.value

        // The strict onset condition — unchanged: only ever used to open a new event from IDLE.
        val onsetTriggered = blockIndex >= config.warmupBlocks &&
            TriggerEvaluator.shouldTrigger(signal.dbfs, signal.spike, signal.crest, signal.clipRatio, config)
        // The looser release condition — only ever used to decide whether an already-open event
        // is still active; never reapplies the full onset trigger (crest/clip/dbfs) to a
        // candidate that has already started. Peak-relative, not baseline-relative: a frozen
        // background baseline can sit far below the real room level, so comparing against it
        // (as spike does) could keep ordinary room noise "active" long after a short impulse has
        // actually ended. Comparing against the candidate's own strongest block so far avoids
        // that regardless of how far off the baseline is. eventPeakDbfs here is still the peak as
        // it stood *before* this block — handleActiveBlock() below updates it afterward, so a
        // later, louder active block raises the release boundary for every block that follows it.
        val stillActive = signal.dbfs > eventPeakDbfs - config.releaseDropDb

        // Baseline reflects the established background, not the candidate block itself:
        // a block that starts a detection must not be allowed to raise its own reference.
        if (stateAtEntry == DetectionState.IDLE && !onsetTriggered) {
            baseline.update(signal.dbfs)
        }

        val event = when (stateAtEntry) {
            DetectionState.IDLE -> handleIdle(onsetTriggered, signal.dbfs, blockIndex)
            DetectionState.DETECTING -> handleDetecting(stillActive, signal.dbfs, blockIndex)
            DetectionState.COOLDOWN -> handleCooldown()
        }

        lastBlockMetrics = BlockMetrics(
            rms = signal.rms,
            dbfs = signal.dbfs,
            baseline = baselineUsedForSpike,
            spike = signal.spike,
            crest = signal.crest,
            clipRatio = signal.clipRatio,
            state = state,
            blockIndex = blockIndex
        )

        return event
    }

    private fun analyzeBlock(block: ShortArray): BlockSignal {
        val level = AudioLevelCalculator.calculate(block, config.dbfsFloor)
        val clipRatio = ClippingCalculator.calculateClipRatio(block, config.clipLevel)
        crestTracker.addBlock(block)
        val crest = crestTracker.currentCrest()
        val spike = SpikeCalculator.calculateSpike(level.dbfs, baseline.value)

        return BlockSignal(level.rms, level.dbfs, clipRatio, crest, spike)
    }

    private fun handleIdle(onsetTriggered: Boolean, dbfs: Double, blockIndex: Long): DetectionEvent? {
        if (onsetTriggered) {
            state = DetectionState.DETECTING
            eventStartBlockIndex = blockIndex
            lastActiveBlockIndex = blockIndex
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
            consecutiveInactiveBlocks = 0
        }
        return null
    }

    private fun handleDetecting(stillActive: Boolean, dbfs: Double, blockIndex: Long): DetectionEvent? =
        if (stillActive) handleActiveBlock(dbfs, blockIndex) else handleInactiveBlock()

    private fun handleActiveBlock(dbfs: Double, blockIndex: Long): DetectionEvent? {
        lastActiveBlockIndex = blockIndex
        consecutiveInactiveBlocks = 0
        if (dbfs > eventPeakDbfs) {
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
        }

        // Span from onset to the latest active block, inclusive — not a count of active blocks,
        // so any inactive gaps already inside the event count toward it too. Compared as Long
        // before any narrowing conversion, since both block indices are Long and their
        // difference could in principle exceed Int range.
        val durationBlocksLong = blockIndex - eventStartBlockIndex + 1L
        if (durationBlocksLong <= config.maxEventDurationBlocks.toLong()) {
            return null
        }

        // This active block is the one that pushed the span over the limit, so its own effect on
        // the peak/duration above belongs to the rejected candidate, not to whatever comes next.
        // durationBlocks stays Int (unchanged public shape); clamp rather than let toInt() wrap.
        val durationBlocks = durationBlocksLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val rejection = CandidateCompletion.Rejected(
            reason = CandidateRejectionReason.TOO_LONG,
            peakDbfs = eventPeakDbfs,
            peakBlockIndex = eventPeakBlockIndex,
            durationBlocks = durationBlocks
        )
        finishEventState()
        resetEventTracking()
        lastCandidateCompletion = rejection
        return null
    }

    private fun handleInactiveBlock(): DetectionEvent? {
        consecutiveInactiveBlocks++
        if (consecutiveInactiveBlocks < config.endSilenceBlocks) {
            return null
        }

        // Up to the last block that was actually still active — the trailing inactive
        // confirmation blocks that closed the event are not part of its duration. Same Long-first
        // calculation as the active-block path, for the same overflow reason.
        val durationBlocksLong = lastActiveBlockIndex - eventStartBlockIndex + 1L
        val durationBlocks = durationBlocksLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val event = DetectionEvent(
            peakDbfs = eventPeakDbfs,
            peakBlockIndex = eventPeakBlockIndex,
            durationBlocks = durationBlocks
        )
        finishEventState()
        resetEventTracking()
        lastCandidateCompletion = CandidateCompletion.Accepted(event)
        return event
    }

    private fun handleCooldown(): DetectionEvent? {
        cooldownBlockCount++
        if (cooldownBlockCount >= config.cooldownBlocks) {
            state = DetectionState.IDLE
        }
        return null
    }

    /** Leaves `DETECTING` for whichever candidate just finished — accepted or rejected alike. */
    private fun finishEventState() {
        state = if (config.cooldownBlocks == 0) DetectionState.IDLE else DetectionState.COOLDOWN
        cooldownBlockCount = 0
        consecutiveInactiveBlocks = 0
    }

    private fun resetEventTracking() {
        eventStartBlockIndex = 0L
        lastActiveBlockIndex = 0L
        eventPeakDbfs = Double.NEGATIVE_INFINITY
        eventPeakBlockIndex = 0L
    }
}
