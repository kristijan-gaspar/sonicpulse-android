package hr.sonicpulse.engine

import hr.sonicpulse.engine.metrics.AudioLevelCalculator
import hr.sonicpulse.engine.metrics.BaselineTracker
import hr.sonicpulse.engine.metrics.ClippingCalculator
import hr.sonicpulse.engine.metrics.CrestFactorTracker
import hr.sonicpulse.engine.metrics.SpikeCalculator
import hr.sonicpulse.engine.metrics.TriggerEvaluator

class DetectionEngine(private val config: EngineConfig = EngineConfig()) {

    private enum class State { IDLE, DETECTING, COOLDOWN }

    private data class BlockSignal(
        val dbfs: Double,
        val clipRatio: Double,
        val crest: Double?,
        val spike: Double
    )

    private var state = State.IDLE
    private var processedBlockIndex = 0L
    private var consecutiveNonTriggerBlocks = 0
    private var cooldownBlockCount = 0
    private var eventPeakDbfs = Double.NEGATIVE_INFINITY
    private var eventPeakBlockIndex = 0L

    private val baseline = BaselineTracker(config.alphaDown, config.alphaUp)
    private val crestTracker = CrestFactorTracker(config.crestWindowBlocks)

    val currentBaseline: Double get() = baseline.value

    fun process(block: ShortArray): DetectionEvent? {
        require(block.size == config.blockSize) {
            "Block size must be ${config.blockSize}, was ${block.size}."
        }

        val blockIndex = processedBlockIndex
        processedBlockIndex++

        val stateAtEntry = state
        val signal = analyzeBlock(block)
        val triggered = blockIndex >= config.warmupBlocks &&
            TriggerEvaluator.shouldTrigger(signal.dbfs, signal.spike, signal.crest, signal.clipRatio, config)

        // Baseline reflects the established background, not the candidate block itself:
        // a block that starts a detection must not be allowed to raise its own reference.
        if (stateAtEntry == State.IDLE && !triggered) {
            baseline.update(signal.dbfs)
        }

        return when (stateAtEntry) {
            State.IDLE -> handleIdle(triggered, signal.dbfs, blockIndex)
            State.DETECTING -> handleDetecting(triggered, signal.dbfs, blockIndex)
            State.COOLDOWN -> handleCooldown()
        }
    }

    private fun analyzeBlock(block: ShortArray): BlockSignal {
        val level = AudioLevelCalculator.calculate(block, config.dbfsFloor)
        val clipRatio = ClippingCalculator.calculateClipRatio(block, config.clipLevel)
        crestTracker.addBlock(block)
        val crest = crestTracker.currentCrest()
        val spike = SpikeCalculator.calculateSpike(level.dbfs, baseline.value)

        return BlockSignal(level.dbfs, clipRatio, crest, spike)
    }

    private fun handleIdle(triggered: Boolean, dbfs: Double, blockIndex: Long): DetectionEvent? {
        if (triggered) {
            state = State.DETECTING
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

        state = State.COOLDOWN
        cooldownBlockCount = 0
        return DetectionEvent(peakDbfs = eventPeakDbfs, peakBlockIndex = eventPeakBlockIndex)
    }

    private fun handleCooldown(): DetectionEvent? {
        cooldownBlockCount++
        if (cooldownBlockCount >= config.cooldownBlocks) {
            state = State.IDLE
        }
        return null
    }
}
