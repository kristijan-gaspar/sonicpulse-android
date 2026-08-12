package hr.sonicpulse.engine.adaptive

/**
 * Dufaux-derived robust background-variation signal for a single evaluation: the plain
 * median [medianPower] (`mf`), the conditional median [conditionalMedianPower] (`cmf`),
 * and their [difference] (`cmf - mf`).
 *
 * This iteration only exposes the signal; it is not yet folded into the active
 * threshold, so [difference] carries no formula beyond the plain subtraction.
 */
data class RobustBackgroundVariation(
    val medianPower: Double,
    val conditionalMedianPower: Double,
    val difference: Double
) {
    init {
        require(medianPower.isFinite() && medianPower >= 0.0) {
            "medianPower must be finite and non-negative, was $medianPower."
        }
        require(conditionalMedianPower.isFinite() && conditionalMedianPower >= 0.0) {
            "conditionalMedianPower must be finite and non-negative, was $conditionalMedianPower."
        }
        require(difference.isFinite()) { "difference must be finite, was $difference." }
    }
}
