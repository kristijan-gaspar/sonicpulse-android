package hr.sonicpulse.engine.adaptive

/** Immutable summary of the causal background power history at a point in time. */
data class BackgroundStatistics(
    val meanPower: Double,
    val stdPower: Double,
    val sampleCount: Int
) {
    init {
        require(meanPower.isFinite() && meanPower >= 0.0) {
            "meanPower must be finite and non-negative, was $meanPower."
        }
        require(stdPower.isFinite() && stdPower >= 0.0) {
            "stdPower must be finite and non-negative, was $stdPower."
        }
        require(sampleCount >= 0) { "sampleCount must be non-negative, was $sampleCount." }
    }
}
