package hr.sonicpulse.engine.adaptive

/**
 * Wires the Dufaux Method-3 robust adaptive threshold together from its collaborators —
 * background history/chronological access ([backgroundEstimator]), conditional-median
 * calculation (delegated internally by [AdaptiveBackgroundEstimator.robustVariation] to
 * [ConditionalMedianCalculator]), the rolling variation-threshold history
 * ([variationHistory]) implementing Eq. 3.9, and final threshold evaluation
 * ([thresholdEvaluator]) — into the single active detection threshold `T(k) = mfa(k) + th(k)`.
 *
 * Bootstrap: before [RobustVariationThresholdHistory.isReady], there is no real `th(k-1)`
 * yet, so both the conditional-median reference threshold `tha(k)` and the active `th(k)`
 * fall back to the classic `thresholdStdMultiplier * stdPower` — a SonicPulse
 * initialization adaptation, not a claim from Dufaux's paper. Once the rolling variation
 * history is ready, `stdPower` no longer contributes to the active threshold; it stays
 * available on [BackgroundStatistics] purely for diagnostics/logging.
 *
 * [evaluate] is purely a read: it never mutates [backgroundEstimator] or
 * [variationHistory]. Admission of the current power into background history
 * ([AdaptiveBackgroundEstimator.addObservation]) and admission of the computed variation
 * into the rolling variation history ([RobustVariationThresholdHistory.addVariation])
 * remain the caller's explicit, separate decisions — this class does not call either.
 */
class AdaptiveRobustThresholdCalculator(
    private val backgroundEstimator: AdaptiveBackgroundEstimator,
    private val variationHistory: RobustVariationThresholdHistory,
    private val thresholdEvaluator: AdaptiveThresholdEvaluator
) {

    fun evaluate(currentPower: Double): RobustThresholdEvaluation? {
        val statistics = backgroundEstimator.statistics ?: return null
        val bootstrapValue = thresholdEvaluator.thresholdStdMultiplier * statistics.stdPower
        val variationHistoryReady = variationHistory.isReady

        val tha = if (variationHistoryReady) requireNotNull(variationHistory.threshold) else bootstrapValue
        val variationResult = backgroundEstimator.robustVariation(conditionalMedianThreshold = tha)
            ?: return null
        val variation = variationResult.difference

        val th = if (variationHistoryReady) variationHistory.thresholdIncluding(variation) else bootstrapValue
        val threshold = thresholdEvaluator.calculateRobustThreshold(statistics.medianPower, th)
        val exceeds = thresholdEvaluator.exceedsRobustThreshold(currentPower, statistics.medianPower, th)

        return RobustThresholdEvaluation(
            mfa = statistics.medianPower,
            stdPower = statistics.stdPower,
            tha = tha,
            cmfa = variationResult.conditionalMedianPower,
            th = th,
            variation = variation,
            threshold = threshold,
            exceedsThreshold = exceeds,
            isBootstrapping = !variationHistoryReady
        )
    }
}
