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

    /**
     * Dufaux-style conditional-median background-variation signal over the retained
     * history, for the explicit [conditionalMedianThreshold] (`tha`). The reference power
     * is not supplied by the caller: it is the causal delayed sample `e(k-d)` selected
     * internally from the retained chronological history, with delay `d = L/2 - 1` for
     * even `L` (Dufaux's causal median-filter delay). Read-only: does not mutate
     * background state. `null` before the history is full, matching [statistics].
     */
    fun robustVariation(conditionalMedianThreshold: Double): RobustBackgroundVariation? {
        if (!isReady) return null
        return ConditionalMedianCalculator.evaluate(history, delayedSample(), conditionalMedianThreshold)
    }

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

    /**
     * `e(k-d)`: the retained sample `d = capacity/2 - 1` positions behind the newest one,
     * in chronological (not sorted) order — the same `writeIndex`-relative indexing
     * [RollingAnalysisWindow] uses for its chronological view.
     */
    private fun delayedSample(): Double {
        val d = capacity / 2 - 1
        val chronologicalIndexFromOldest = capacity - 1 - d
        val physicalIndex = (writeIndex + chronologicalIndexFromOldest) % capacity
        return history[physicalIndex]
    }
}
