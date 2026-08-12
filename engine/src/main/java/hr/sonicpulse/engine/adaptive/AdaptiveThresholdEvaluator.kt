package hr.sonicpulse.engine.adaptive

/**
 * Calculates the active adaptive linear-power threshold and evaluates the strict energy
 * criterion `P > T`. Owns no history and mutates nothing; it only reads the values handed
 * to it.
 *
 * Two threshold formulas are supported:
 * - The classic path, `T = m + K * std` ([calculateThreshold]), kept for
 *   diagnostics/logging comparison.
 * - The Dufaux Method-3 robust path, `T = mfa + th` ([calculateRobustThreshold]), which is
 *   the active detection threshold once the robust adaptive path is ready (see
 *   [AdaptiveRobustThresholdCalculator]).
 *
 * [thresholdStdMultiplier] has no default here — [AdaptiveEngineConfig.thresholdStdMultiplier]
 * is the single source of truth for `K`, and callers must pass it explicitly.
 */
class AdaptiveThresholdEvaluator(val thresholdStdMultiplier: Double) {

    init {
        require(thresholdStdMultiplier > 0.0) {
            "thresholdStdMultiplier must be positive, was $thresholdStdMultiplier."
        }
    }

    fun calculateThreshold(statistics: BackgroundStatistics): Double =
        statistics.medianPower + thresholdStdMultiplier * statistics.stdPower

    fun exceedsThreshold(currentPower: Double, statistics: BackgroundStatistics): Boolean {
        require(currentPower.isFinite() && currentPower >= 0.0) {
            "currentPower must be finite and non-negative, was $currentPower."
        }
        return currentPower > calculateThreshold(statistics)
    }

    /** `T(k) = mfa(k) + th(k)`, the Dufaux Method-3 robust adaptive threshold. */
    fun calculateRobustThreshold(mfa: Double, th: Double): Double {
        require(mfa.isFinite() && mfa >= 0.0) { "mfa must be finite and non-negative, was $mfa." }
        require(th.isFinite()) { "th must be finite, was $th." }
        return mfa + th
    }

    fun exceedsRobustThreshold(currentPower: Double, mfa: Double, th: Double): Boolean {
        require(currentPower.isFinite() && currentPower >= 0.0) {
            "currentPower must be finite and non-negative, was $currentPower."
        }
        return currentPower > calculateRobustThreshold(mfa, th)
    }
}
