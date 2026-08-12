package hr.sonicpulse.engine.adaptive

/**
 * Calculates the adaptive linear-power threshold `T = m + K * sigma` from a
 * [BackgroundStatistics] snapshot and evaluates the strict energy criterion `P > T`.
 *
 * Owns no history and mutates nothing; it only reads the statistics handed to it.
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
}
