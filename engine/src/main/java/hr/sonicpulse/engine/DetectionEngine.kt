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
    private var consecutiveNonTriggerBlocks = 0
    private var cooldownBlockCount = 0
    private var eventPeakDbfs = Double.NEGATIVE_INFINITY
    private var eventPeakBlockIndex = 0L

    private val baseline = BaselineTracker(config.alphaDown, config.alphaUp)
    private val crestTracker = CrestFactorTracker(config.crestWindowBlocks)

    val currentBaseline: Double get() = baseline.value

    var lastBlockMetrics: BlockMetrics? = null
        private set

    fun process(block: ShortArray): DetectionEvent? {
        require(block.size == config.blockSize) {
            "Block size must be ${config.blockSize}, was ${block.size}."
        }

        val blockIndex = processedBlockIndex
        processedBlockIndex++

        val stateAtEntry = state
        val signal = analyzeBlock(block)
        val baselineUsedForSpike = baseline.value
        val triggered = blockIndex >= config.warmupBlocks &&
            TriggerEvaluator.shouldTrigger(signal.dbfs, signal.spike, signal.crest, signal.clipRatio, config)

        // Baseline reflects the established background, not the candidate block itself:
        // a block that starts a detection must not be allowed to raise its own reference.
        if (stateAtEntry == DetectionState.IDLE && !triggered) {
            baseline.update(signal.dbfs)
        }

        val event = when (stateAtEntry) {
            DetectionState.IDLE -> handleIdle(triggered, signal.dbfs, blockIndex)
            DetectionState.DETECTING -> handleDetecting(triggered, signal.dbfs, blockIndex)
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

    private fun handleIdle(triggered: Boolean, dbfs: Double, blockIndex: Long): DetectionEvent? {
        if (triggered) {
            state = DetectionState.DETECTING
            consecutiveNonTriggerBlocks = 0
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
        }
        return null
    }

    private fun handleDetecting(triggered: Boolean, dbfs: Double, blockIndex: Long): DetectionEvent? {
        if (dbfs > eventPeakDbfs) {
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
        }

        if (triggered) {
            consecutiveNonTriggerBlocks = 0
            return null
        }

        consecutiveNonTriggerBlocks++
        if (consecutiveNonTriggerBlocks < config.endSilenceBlocks) {
            return null
        }

        state = if (config.cooldownBlocks == 0) DetectionState.IDLE else DetectionState.COOLDOWN
        cooldownBlockCount = 0
        return DetectionEvent(peakDbfs = eventPeakDbfs, peakBlockIndex = eventPeakBlockIndex)
    }

    private fun handleCooldown(): DetectionEvent? {
        cooldownBlockCount++
        if (cooldownBlockCount >= config.cooldownBlocks) {
            state = DetectionState.IDLE
        }
        return null
    }
}
