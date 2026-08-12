package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.metrics.AudioLevelCalculator
import hr.sonicpulse.engine.metrics.ClippingCalculator

/**
 * Orchestrates the V2 adaptive detection pipeline for one hop at a time: DSP (rolling
 * analysis window, linear power), the Dufaux robust adaptive threshold, and the
 * admission/freeze decisions for background history — then hands the computed values to
 * [AdaptiveDetectionStateMachine], which owns event lifecycle alone.
 *
 * Locked onset semantics:
 * ```
 * energyExceeded = currentPower > adaptiveThreshold
 * impulsive      = crestDb > crestMinDb || clipRatio > clipRatioMin
 * trigger        = energyExceeded && impulsive
 * ```
 *
 * Background ownership (only this class decides admission, never the state machine):
 * - `IDLE`, no trigger: admit the current power into [AdaptiveBackgroundEstimator] and
 *   the current variation into [RobustVariationThresholdHistory].
 * - `IDLE`, on the triggering hop itself: admit neither.
 * - `DETECTING` / `COOLDOWN`: freeze both — neither history is touched.
 *
 * dBFS and clip ratio reuse V1's pure, stateless `AudioLevelCalculator` /
 * `ClippingCalculator` utilities unchanged, at the same 1024-sample-per-block
 * granularity V1 uses; crest factor is computed by [CrestFactorCalculator], V2's own
 * single-hop implementation (see its doc for why it doesn't reuse V1's windowed tracker).
 */
class AdaptiveDetectionEngine(private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()) {

    private val analysisWindow = RollingAnalysisWindow(config)
    private val backgroundEstimator = AdaptiveBackgroundEstimator(config)
    private val variationHistory = RobustVariationThresholdHistory(config)
    private val thresholdEvaluator = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
    private val robustThresholdCalculator =
        AdaptiveRobustThresholdCalculator(backgroundEstimator, variationHistory, thresholdEvaluator)
    private val stateMachine = AdaptiveDetectionStateMachine(config)

    private var processedHopIndex = 0L

    val state: AdaptiveDetectionState get() = stateMachine.state

    fun process(hop: ShortArray): DetectionEvent? {
        require(hop.size == config.hopSize) {
            "hop must have exactly ${config.hopSize} samples, was ${hop.size}."
        }

        val hopIndex = processedHopIndex
        processedHopIndex++

        // Still filling the 4096-sample analysis window: no power yet, nothing to decide.
        val window = analysisWindow.update(hop) ?: return null

        val currentPower = PowerCalculator.calculate(window)
        val currentDbfs = AudioLevelCalculator.calculate(hop).dbfs
        val clipRatio = ClippingCalculator.calculateClipRatio(hop, config.clipLevel)
        val crestDb = CrestFactorCalculator.calculate(hop)

        val evaluation = robustThresholdCalculator.evaluate(currentPower)
        val energyExceeded = evaluation?.exceedsThreshold ?: false
        val impulsive = (crestDb != null && crestDb > config.crestMinDb) || clipRatio > config.clipRatioMin
        val trigger = energyExceeded && impulsive

        val stateAtEntry = stateMachine.state
        val event = stateMachine.process(
            trigger = trigger,
            currentPower = currentPower,
            currentDbfs = currentDbfs,
            blockIndex = hopIndex,
            adaptiveThreshold = evaluation?.threshold ?: 0.0
        )

        if (stateAtEntry == AdaptiveDetectionState.IDLE && !trigger) {
            backgroundEstimator.addObservation(currentPower)
            evaluation?.let { variationHistory.addVariation(it.variation) }
        }

        return event
    }

    fun reset() {
        analysisWindow.reset()
        backgroundEstimator.reset()
        variationHistory.reset()
        stateMachine.reset()
        processedHopIndex = 0L
    }
}
