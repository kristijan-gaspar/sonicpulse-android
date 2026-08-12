package hr.sonicpulse.engine.adaptive

import kotlin.math.sqrt

/**
 * Owns a bounded, causal history of previous background linear-power observations and
 * exposes their mean/standard deviation as [BackgroundStatistics].
 *
 * Robustness: before an observation is retained, it passes through a causal median-of-3
 * filter over the raw observation stream (the current value and the two immediately
 * preceding it). A single isolated high-power observation is always the minority value
 * in that 3-sample window, so its median is one of its stable neighbors — the spike is
 * filtered out and never contaminates the retained history. This needs no extra
 * coefficients, thresholds or separately configured time window: 3 is the smallest
 * window for which "median" differs from "raw value", and it runs over the same
 * per-hop observation stream the ~5 s history is already built from.
 *
 * [addObservation] is the only way history changes; nothing else in this class mutates
 * state, so callers fully control when an observation is allowed into the background.
 */
class AdaptiveBackgroundEstimator(private val config: AdaptiveEngineConfig) {

    private val capacity = config.backgroundHistoryCapacity
    private val history = DoubleArray(capacity)
    private var writeIndex = 0
    private var filledCount = 0

    private var pendingRaw1: Double? = null
    private var pendingRaw2: Double? = null

    val isReady: Boolean get() = filledCount >= capacity

    val statistics: BackgroundStatistics?
        get() = if (isReady) computeStatistics() else null

    fun addObservation(power: Double) {
        require(power.isFinite() && power >= 0.0) {
            "power must be finite and non-negative, was $power."
        }

        val filtered = advanceMedianFilter(power) ?: return
        history[writeIndex] = filtered
        writeIndex = (writeIndex + 1) % capacity
        filledCount = (filledCount + 1).coerceAtMost(capacity)
    }

    fun reset() {
        history.fill(0.0)
        writeIndex = 0
        filledCount = 0
        pendingRaw1 = null
        pendingRaw2 = null
    }

    private fun advanceMedianFilter(power: Double): Double? {
        val previous1 = pendingRaw1
        val previous2 = pendingRaw2
        pendingRaw1 = pendingRaw2
        pendingRaw2 = power

        return if (previous1 != null && previous2 != null) {
            medianOf3(previous1, previous2, power)
        } else {
            null
        }
    }

    private fun medianOf3(a: Double, b: Double, c: Double): Double =
        listOf(a, b, c).sorted()[1]

    private fun computeStatistics(): BackgroundStatistics {
        var sum = 0.0
        for (i in 0 until capacity) sum += history[i]
        val mean = sum / capacity

        var sumOfSquaredDeviations = 0.0
        for (i in 0 until capacity) {
            val deviation = history[i] - mean
            sumOfSquaredDeviations += deviation * deviation
        }
        val std = sqrt(sumOfSquaredDeviations / capacity)

        return BackgroundStatistics(meanPower = mean, stdPower = std, sampleCount = capacity)
    }
}
