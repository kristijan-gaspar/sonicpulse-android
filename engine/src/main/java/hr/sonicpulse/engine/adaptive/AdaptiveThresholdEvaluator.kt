package hr.sonicpulse.engine.adaptive

/**
 * Calculates the adaptive linear-power threshold `T = mean + K * std` from a
 * [BackgroundStatistics] snapshot and evaluates the strict energy criterion `P > T`.
 *
 * Owns no history and mutates nothing; it only reads the statistics handed to it.
 */
class AdaptiveThresholdEvaluator(val thresholdStdMultiplier: Double = 5.0) {

    init {
        require(thresholdStdMultiplier > 0.0) {
            "thresholdStdMultiplier must be positive, was $thresholdStdMultiplier."
        }
    }

    fun calculateThreshold(statistics: BackgroundStatistics): Double =
        statistics.meanPower + thresholdStdMultiplier * statistics.stdPower

    fun exceedsThreshold(currentPower: Double, statistics: BackgroundStatistics): Boolean {
        require(currentPower.isFinite() && currentPower >= 0.0) {
            "currentPower must be finite and non-negative, was $currentPower."
        }
        return currentPower > calculateThreshold(statistics)
    }
}
