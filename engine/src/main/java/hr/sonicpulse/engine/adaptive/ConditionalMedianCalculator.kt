package hr.sonicpulse.engine.adaptive

import kotlin.math.abs

/**
 * Dufaux-style median / conditional-median processing over a supplied linear-power
 * window. Stateless and read-only: neither function mutates or retains the window.
 */
object ConditionalMedianCalculator {

    /**
     * `mf = median(window)`, using the same `sorted[size / 2]` convention as
     * [AdaptiveBackgroundEstimator] (central value for an odd count, the higher of the
     * two central values for an even count).
     */
    fun median(window: DoubleArray): Double {
        require(window.isNotEmpty()) { "window must not be empty." }

        val sorted = window.copyOf()
        sorted.sort()
        return sorted[sorted.size / 2]
    }

    /**
     * `cmf = mf` when `|referencePower - mf|` exceeds [conditionalMedianThreshold];
     * otherwise `cmf = referencePower`. [conditionalMedianThreshold] must be supplied
     * explicitly — there is no built-in default.
     */
    fun evaluate(
        window: DoubleArray,
        referencePower: Double,
        conditionalMedianThreshold: Double
    ): RobustBackgroundVariation {
        require(referencePower.isFinite() && referencePower >= 0.0) {
            "referencePower must be finite and non-negative, was $referencePower."
        }
        require(conditionalMedianThreshold.isFinite() && conditionalMedianThreshold >= 0.0) {
            "conditionalMedianThreshold must be finite and non-negative, was $conditionalMedianThreshold."
        }

        val mf = median(window)
        val cmf = if (abs(referencePower - mf) > conditionalMedianThreshold) mf else referencePower

        return RobustBackgroundVariation(
            medianPower = mf,
            conditionalMedianPower = cmf,
            difference = cmf - mf
        )
    }
}
