package hr.sonicpulse.engine.adaptive

/**
* Calculates the active Dufaux Method-3 robust adaptive linear-power threshold,
 * `T(k) = mfa(k) + th(k)`, and evaluates the strict energy criterion `P > T`. Owns no
 * history and mutates nothing; it only reads the values handed to it.
 */
class AdaptiveThresholdEvaluator {

    /** `T(k) = mfa(k) + th(k)`, the Dufaux Method-3 robust adaptive threshold. */
    fun calculateThreshold(mfa: Double, th: Double): Double {
        require(mfa.isFinite() && mfa >= 0.0) { "mfa must be finite and non-negative, was $mfa." }
        require(th.isFinite() && th >= 0.0) { "th must be finite and non-negative, was $th." }
        return mfa + th
    }

    fun exceedsThreshold(currentPower: Double, mfa: Double, th: Double): Boolean {
        require(currentPower.isFinite() && currentPower >= 0.0) {
            "currentPower must be finite and non-negative, was $currentPower."
        }
        return currentPower > calculateThreshold(mfa, th)
    }
}
