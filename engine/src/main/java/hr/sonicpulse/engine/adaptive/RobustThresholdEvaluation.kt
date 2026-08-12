package hr.sonicpulse.engine.adaptive

/**
 * Snapshot of one Dufaux Method-3 robust threshold evaluation: `mfa(k)`, `th(k)`, the
 * resulting `variation(k) = cmfa(k) - mfa(k)`, the final threshold `T(k) = mfa(k) + th(k)`,
 * and the strict trigger decision for the evaluated power. [isBootstrapping] is `true`
 * while the rolling variation-threshold history has not yet accumulated `D` observations,
 * during which `th` falls back to the classic `thresholdStdMultiplier * stdPower` (see
 * [AdaptiveRobustThresholdCalculator]).
 */
data class RobustThresholdEvaluation(
    val mfa: Double,
    val th: Double,
    val variation: Double,
    val threshold: Double,
    val exceedsThreshold: Boolean,
    val isBootstrapping: Boolean
) {
    init {
        require(mfa.isFinite() && mfa >= 0.0) { "mfa must be finite and non-negative, was $mfa." }
        require(th.isFinite()) { "th must be finite, was $th." }
        require(variation.isFinite()) { "variation must be finite, was $variation." }
        require(threshold.isFinite()) { "threshold must be finite, was $threshold." }
    }
}
