package hr.sonicpulse.engine.adaptive

/**
 * Owns the bounded causal rolling history of Dufaux background-variation values used by
 * Eq. 3.9: `th(k) = ov * max(variation(i), i = k-D+1..k)`.
 *
 * [addVariation] is the only way this history changes, mirroring
 * [AdaptiveBackgroundEstimator]'s explicit `addObservation` ownership model: nothing here
 * decides on its own whether a computed variation is admitted.
 */
class RobustVariationThresholdHistory(private val config: AdaptiveEngineConfig) {

    private val capacity = config.variationHistoryCapacity
    private val history = DoubleArray(capacity)
    private var writeIndex = 0
    private var filledCount = 0

    val isReady: Boolean get() = filledCount >= capacity

    /** `th`, based only on variation values already explicitly added. `null` until ready. */
    val threshold: Double?
        get() = if (isReady) config.ov * maxOfRetained(candidate = null, excludeOldest = false) else null

    /**
     * The Eq. 3.9 threshold as it would be if [candidateVariation] were also included in
     * the rolling max window — without mutating this history. This lets a caller compute
     * `th(k)`, which by Eq. 3.9 includes the current step's own variation(k), while still
     * leaving admission of variation(k) into the rolling history as an explicit, separate
     * decision via [addVariation].
     *
     * Once full, actually admitting [candidateVariation] via [addVariation] would evict
     * the oldest retained value (FIFO). This must mirror that eviction so the window
     * always stays exactly `D` values — the newest `D-1` retained plus the candidate —
     * without mutating the history to compute it.
     */
    fun thresholdIncluding(candidateVariation: Double): Double {
        require(candidateVariation.isFinite()) {
            "candidateVariation must be finite, was $candidateVariation."
        }
        return config.ov * maxOfRetained(candidate = candidateVariation, excludeOldest = isReady)
    }

    fun addVariation(value: Double) {
        require(value.isFinite()) { "value must be finite, was $value." }

        history[writeIndex] = value
        writeIndex = (writeIndex + 1) % capacity
        filledCount = (filledCount + 1).coerceAtMost(capacity)
    }

    fun reset() {
        history.fill(0.0)
        writeIndex = 0
        filledCount = 0
    }

    /**
     * [excludeOldest] skips the physical slot holding the oldest retained value (which,
     * once full, is exactly [writeIndex] — the next slot [addVariation] would overwrite).
     */
    private fun maxOfRetained(candidate: Double?, excludeOldest: Boolean): Double {
        val oldestIndex = if (excludeOldest) writeIndex else -1
        var max = candidate ?: Double.NEGATIVE_INFINITY
        for (i in 0 until filledCount) {
            if (i == oldestIndex) continue
            if (history[i] > max) max = history[i]
        }
        return max
    }
}
