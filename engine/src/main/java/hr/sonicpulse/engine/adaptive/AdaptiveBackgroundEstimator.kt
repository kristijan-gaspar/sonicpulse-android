package hr.sonicpulse.engine.adaptive

import kotlin.math.sqrt

/**
 * Owns a bounded, causal history of previous accepted background linear-power
 * observations and exposes it as [BackgroundStatistics].
 *
 * The background level is the causal long-term median of the retained history (Dufaux's
 * long-term median background estimate): once full, the retained observations are
 * sorted and the value at index `size / 2` is taken. For an odd retained count this is
 * the central sorted value; for an even count, integer division on `size / 2` lands on
 * the upper of the two central sorted values, matching Dufaux's convention. A single
 * isolated high-power observation shifts at most one position in that sorted order, so
 * with a sufficiently populated history it cannot dominate the median.
 *
 * [addObservation] is the only way history changes; nothing else in this class mutates
 * state, so callers fully control when an observation is allowed into the background.
 */
class AdaptiveBackgroundEstimator(private val config: AdaptiveEngineConfig) {

    private val capacity = config.backgroundHistoryCapacity
    private val history = DoubleArray(capacity)
    private var writeIndex = 0
    private var filledCount = 0

    val isReady: Boolean get() = filledCount >= capacity

    val statistics: BackgroundStatistics?
        get() = if (isReady) computeStatistics() else null

    fun addObservation(power: Double) {
        require(power.isFinite() && power >= 0.0) {
            "power must be finite and non-negative, was $power."
        }

        history[writeIndex] = power
        writeIndex = (writeIndex + 1) % capacity
        filledCount = (filledCount + 1).coerceAtMost(capacity)
    }

    fun reset() {
        history.fill(0.0)
        writeIndex = 0
        filledCount = 0
    }

    private fun computeStatistics(): BackgroundStatistics {
        val sorted = history.copyOf()
        sorted.sort()
        val medianPower = sorted[capacity / 2]

        var sum = 0.0
        for (i in 0 until capacity) sum += history[i]
        val arithmeticMean = sum / capacity

        var sumOfSquaredDeviations = 0.0
        for (i in 0 until capacity) {
            val deviation = history[i] - arithmeticMean
            sumOfSquaredDeviations += deviation * deviation
        }
        val std = sqrt(sumOfSquaredDeviations / capacity)

        return BackgroundStatistics(medianPower = medianPower, stdPower = std, sampleCount = capacity)
    }
}
