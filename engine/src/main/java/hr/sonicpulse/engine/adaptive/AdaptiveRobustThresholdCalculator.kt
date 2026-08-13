package hr.sonicpulse.engine.adaptive

/**
 * Wires the Dufaux Method-3 robust adaptive threshold together from its collaborators —
 * background history/chronological access ([backgroundEstimator]), conditional-median
 * calculation (delegated internally by [AdaptiveBackgroundEstimator.robustVariation] to
 * [ConditionalMedianCalculator]), the rolling variation-threshold history
 * ([variationHistory]) implementing Eq. 3.9, and final threshold evaluation
 * ([thresholdEvaluator]) — into the single active detection threshold `T(k) = mfa(k) + th(k)`.
 *
 * Bootstrap: for the ENTIRE variation warm-up period — i.e. until
 * [RobustVariationThresholdHistory.isReady] becomes `true`, not merely until the first
 * variation is admitted — there is no trustworthy `th(k-1)` yet, so the conditional-median
 * reference threshold `tha(k)` falls back to [AdaptiveEngineConfig.initialThaStdMultiplier]
 * `* stdPower` — a SonicPulse initialization/warm-up seed, not a claim from Dufaux's paper.
 * (Switching on `threshold != null`, i.e. as soon as a single variation exists, would let a
 * single early sample collapse the recursion: variation(1)=0 -> th=0 -> tha(2)=0 -> ..., so
 * the switch is deliberately keyed off [RobustVariationThresholdHistory.isReady] instead.)
 * Once warm-up completes, `tha(k) = th(k-1)` (pure Eq. 3.9 recursion) and stdPower no longer
 * participates in `tha` or the active threshold at all — EXCEPT for one SonicPulse recovery
 * case: `th(k-1) = 0.0` is an absorbing state for Eq. 3.9's rolling max once every retained
 * variation is `<= 0` (see [RobustVariationThresholdHistory.threshold]'s `0.0` floor), and
 * feeding `tha(k) = 0.0` back into the conditional-median step suppresses every non-zero
 * deviation from `mf` (any `|referencePower - mf| > 0.0` already exceeds a `0.0` threshold),
 * forcing `cmf = mf` and `variation(k) = 0`; the one case that ISN'T suppressed —
 * `referencePower == mf` exactly — already yields `variation(k) = cmf - mf = 0` on its own too.
 * Either way `variation(k) = 0`, so `th` stays pinned at `0` forever with no way for the
 * ordinary recursion to escape it. To recover from `th=0`, `tha(k)` falls back to the
 * std-based seed whenever `th(k-1) <= 0.0`, even after warm-up. Crucially, the std-based seed
 * only ever feeds `tha` (the conditional-median suppression threshold) — `th(k)` itself always
 * goes through
 * [RobustVariationThresholdHistory.thresholdIncluding], so the std-based seed is never used as
 * the active detection threshold. [RobustThresholdEvaluation.isBootstrapping] mirrors
 * [RobustVariationThresholdHistory.isReady] and governs whether the adaptive engine is allowed
 * to actually use this threshold for detection.
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
        val variationHistoryReady = variationHistory.isReady

        val bootstrapTha = config.initialThaStdMultiplier * statistics.stdPower
        val tha = if (variationHistoryReady) {
            val previousTh = requireNotNull(variationHistory.threshold)
            if (previousTh > 0.0) previousTh else bootstrapTha
        } else {
            bootstrapTha
        }

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
            isBootstrapping = !variationHistoryReady
        )
    }
}
