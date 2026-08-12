package hr.sonicpulse.engine.adaptive

/**
 * Snapshot of one Dufaux Method-3 robust threshold evaluation: `mfa(k)`, `th(k)`, the
 * resulting `variation(k) = cmfa(k) - mfa(k)`, the final threshold `T(k) = mfa(k) + th(k)`,
 * and the strict trigger decision for the evaluated power. [isBootstrapping] is `true`
 * while the rolling variation-threshold history has not yet accumulated `D` observations,
 * during which `th` falls back to the classic `thresholdStdMultiplier * stdPower` (see
 * [AdaptiveRobustThresholdCalculator]).
 *
 * [stdPower], [tha] and [cmfa] carry values this calculation already produces internally,
 * exposed here purely for diagnostics (session-log export, false-positive/false-negative
 * analysis) rather than recomputed by a caller: [stdPower] is the classic background
 * standard deviation (drives the active threshold only while [isBootstrapping]; diagnostics-
 * only afterward), [tha] is the conditional-median threshold actually used this step
 * (`th(k-1)`, or the bootstrap value), and [cmfa] is the conditional-median output itself.
 */
data class RobustThresholdEvaluation(
    val mfa: Double,
    val stdPower: Double,
    val tha: Double,
    val cmfa: Double,
    val th: Double,
    val variation: Double,
    val threshold: Double,
    val exceedsThreshold: Boolean,
    val isBootstrapping: Boolean
) {
    init {
        require(mfa.isFinite() && mfa >= 0.0) { "mfa must be finite and non-negative, was $mfa." }
        require(stdPower.isFinite() && stdPower >= 0.0) {
            "stdPower must be finite and non-negative, was $stdPower."
        }
        require(tha.isFinite() && tha >= 0.0) { "tha must be finite and non-negative, was $tha." }
        require(cmfa.isFinite() && cmfa >= 0.0) { "cmfa must be finite and non-negative, was $cmfa." }
        require(th.isFinite()) { "th must be finite, was $th." }
        require(variation.isFinite()) { "variation must be finite, was $variation." }
        require(threshold.isFinite()) { "threshold must be finite, was $threshold." }
    }
}
