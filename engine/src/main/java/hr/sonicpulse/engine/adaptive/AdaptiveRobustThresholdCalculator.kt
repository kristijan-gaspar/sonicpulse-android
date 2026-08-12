package hr.sonicpulse.engine.adaptive

/**
 * Wires the Dufaux Method-3 robust adaptive threshold together from its collaborators —
 * background history/chronological access ([backgroundEstimator]), conditional-median
 * calculation (delegated internally by [AdaptiveBackgroundEstimator.robustVariation] to
 * [ConditionalMedianCalculator]), the rolling variation-threshold history
 * ([variationHistory]) implementing Eq. 3.9, and final threshold evaluation
 * ([thresholdEvaluator]) — into the single active detection threshold `T(k) = mfa(k) + th(k)`.
 *
 * Bootstrap: before the very first variation is admitted into [variationHistory] (i.e. while
 * [RobustVariationThresholdHistory.threshold] is still `null`), there is no real `th(k-1)`
 * yet, so the conditional-median reference threshold `tha(k)` falls back to
 * [AdaptiveEngineConfig.initialThaStdMultiplier] `* stdPower` — a SonicPulse
 * initialization seed, not a claim from Dufaux's paper. From the first admitted variation
 * onward, `tha(k) = th(k-1)` (pure Eq. 3.9 recursion) and the std-based seed is never
 * consulted again. Crucially, this seed only ever feeds `tha` (the conditional-median
 * suppression threshold) — `th(k)` itself always goes through
 * [RobustVariationThresholdHistory.thresholdIncluding], so the std-based seed is never
 * used as the active detection threshold. [RobustThresholdEvaluation.isBootstrapping]
 * separately tracks [RobustVariationThresholdHistory.isReady] (the shorter warmup window),
 * which governs whether the adaptive engine is allowed to actually use this threshold for
 * detection.
 *
 * [evaluate] is purely a read: it never mutates [backgroundEstimator] or
 * [variationHistory]. Admission of the current power into background history
 * ([AdaptiveBackgroundEstimator.addObservation]) and admission of the computed variation
 * into the rolling variation history ([RobustVariationThresholdHistory.addVariation])
 * remain the caller's explicit, separate decisions — this class does not call either.
 */
class AdaptiveRobustThresholdCalculator(
    private val config: AdaptiveEngineConfig,
    private val backgroundEstimator: AdaptiveBackgroundEstimator,
    private val variationHistory: RobustVariationThresholdHistory,
    private val thresholdEvaluator: AdaptiveThresholdEvaluator
) {

    fun evaluate(currentPower: Double): RobustThresholdEvaluation? {
        val statistics = backgroundEstimator.statistics ?: return null
        val seededThreshold = variationHistory.threshold
        val tha = seededThreshold ?: (config.initialThaStdMultiplier * statistics.stdPower)

        val variationResult = backgroundEstimator.robustVariation(conditionalMedianThreshold = tha)
            ?: return null
        val variation = variationResult.difference

        val th = variationHistory.thresholdIncluding(variation)
        val threshold = thresholdEvaluator.calculateThreshold(statistics.medianPower, th)
        val exceeds = thresholdEvaluator.exceedsThreshold(currentPower, statistics.medianPower, th)

        return RobustThresholdEvaluation(
            mfa = statistics.medianPower,
            th = th,
            variation = variation,
            threshold = threshold,
            exceedsThreshold = exceeds,
            isBootstrapping = !variationHistory.isReady
        )
    }
}
