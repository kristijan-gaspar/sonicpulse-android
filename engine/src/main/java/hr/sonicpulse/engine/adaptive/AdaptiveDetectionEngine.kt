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

    /**
     * dBFS of the most recently processed hop. Updated for every valid-size hop passed to
     * [process] — including the first hops while the 4096-sample analysis window is still
     * filling and no power/trigger decision can be made yet — so callers always have a
     * current level reading, not just once the window is full. `-120.0` (the dBFS floor)
     * before the first hop is ever processed.
     */
    var lastDbfs: Double = -120.0
        private set

    /** Diagnostic snapshot of the most recently processed hop — see [AdaptiveHopDiagnostics].
     * `null` before the first hop is ever processed; always set for every valid-size hop
     * passed to [process], including during the analysis-window warmup. Observational only:
     * nothing in this class's own decisions reads it back. */
    var lastDiagnostics: AdaptiveHopDiagnostics? = null
        private set

    fun process(hop: ShortArray): DetectionEvent? {
        require(hop.size == config.hopSize) {
            "hop must have exactly ${config.hopSize} samples, was ${hop.size}."
        }

        val hopIndex = processedHopIndex
        processedHopIndex++

        lastDbfs = AudioLevelCalculator.calculate(hop).dbfs

        // Still filling the 4096-sample analysis window: no power yet, nothing to decide.
        val window = analysisWindow.update(hop)
        if (window == null) {
            val stateNow = stateMachine.state
            lastDiagnostics = AdaptiveHopDiagnostics(
                hopIndex = hopIndex,
                analysisReady = false,
                dbfs = lastDbfs,
                power = null,
                crestDb = null,
                clipRatio = null,
                mfa = null,
                variation = null,
                th = null,
                threshold = null,
                isBootstrapping = null,
                energyExceeded = null,
                impulsive = null,
                trigger = null,
                stateBefore = stateNow,
                stateAfter = stateNow
            )
            return null
        }

        val currentPower = PowerCalculator.calculate(window)
        val currentDbfs = lastDbfs
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
        val stateAfterProcess = stateMachine.state

        if (stateAtEntry == AdaptiveDetectionState.IDLE && !trigger) {
            backgroundEstimator.addObservation(currentPower)
            evaluation?.let { variationHistory.addVariation(it.variation) }
        }

        lastDiagnostics = AdaptiveHopDiagnostics(
            hopIndex = hopIndex,
            analysisReady = true,
            dbfs = currentDbfs,
            power = currentPower,
            crestDb = crestDb,
            clipRatio = clipRatio,
            mfa = evaluation?.mfa,
            variation = evaluation?.variation,
            th = evaluation?.th,
            threshold = evaluation?.threshold,
            isBootstrapping = evaluation?.isBootstrapping,
            energyExceeded = energyExceeded,
            impulsive = impulsive,
            trigger = trigger,
            stateBefore = stateAtEntry,
            stateAfter = stateAfterProcess
        )

        return event
    }

    fun reset() {
        analysisWindow.reset()
        backgroundEstimator.reset()
        variationHistory.reset()
        stateMachine.reset()
        processedHopIndex = 0L
        lastDbfs = -120.0
        lastDiagnostics = null
    }
}
