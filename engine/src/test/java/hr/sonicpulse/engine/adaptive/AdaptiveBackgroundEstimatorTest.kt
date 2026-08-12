package hr.sonicpulse.engine.adaptive

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimator applies a causal median-of-3 pre-filter to the raw observation stream
 * before a value ever reaches the retained history (see [AdaptiveBackgroundEstimator]).
 * That filter needs two prior raw observations before it can produce its first output,
 * so filling a history of [capacity] requires `capacity + MEDIAN_FILTER_WARMUP` raw
 * `addObservation` calls, not just `capacity` calls. Tests below account for this
 * explicitly rather than hardcoding call counts.
 */
class AdaptiveBackgroundEstimatorTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        backgroundHistoryMillis = 500
    )
    private val capacity = config.backgroundHistoryCapacity

    @Test
    fun `derived test capacity is 5`() {
        assertEquals(5, capacity)
    }

    @Test
    fun `is not ready before history is full`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity + MEDIAN_FILTER_WARMUP - 1) { estimator.addObservation(0.01) }

        assertFalse(estimator.isReady)
        assertNull(estimator.statistics)
    }

    @Test
    fun `is ready exactly when history is full`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity + MEDIAN_FILTER_WARMUP) { estimator.addObservation(0.01) }

        assertTrue(estimator.isReady)
        assertEquals(capacity, estimator.statistics!!.sampleCount)
    }

    @Test
    fun `constant background gives the correct mean`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity + MEDIAN_FILTER_WARMUP) { estimator.addObservation(0.02) }

        assertEquals(0.02, estimator.statistics!!.meanPower, 1e-12)
    }

    @Test
    fun `constant background gives zero standard deviation`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity + MEDIAN_FILTER_WARMUP) { estimator.addObservation(0.02) }

        assertEquals(0.0, estimator.statistics!!.stdPower, 1e-12)
    }

    // Feeding a monotonic ramp r(i) = i makes the median-of-3 filter fully predictable:
    // for any 3 consecutive increasing values, the median is always the middle (i.e.
    // previous) one, so the filtered output at raw call k (k >= 3) is exactly r(k-1).
    private fun rampEstimator(rawCallCount: Int): AdaptiveBackgroundEstimator {
        val estimator = AdaptiveBackgroundEstimator(config)
        for (i in 1..rawCallCount) {
            estimator.addObservation(i.toDouble())
        }
        return estimator
    }

    @Test
    fun `deterministic ramp values give the correct arithmetic mean on first fill`() {
        // 7 raw calls (capacity 5 + warmup 2) retain filtered outputs r(2)..r(6) = 2..6.
        val estimator = rampEstimator(capacity + MEDIAN_FILTER_WARMUP)

        assertEquals(4.0, estimator.statistics!!.meanPower, 1e-12)
    }

    @Test
    fun `deterministic ramp values give the correct population standard deviation on first fill`() {
        val estimator = rampEstimator(capacity + MEDIAN_FILTER_WARMUP)

        // Retained set {2,3,4,5,6}, mean 4: population std = sqrt(mean((x-4)^2)) = sqrt(2).
        assertEquals(sqrt(2.0), estimator.statistics!!.stdPower, 1e-12)
    }

    @Test
    fun `oldest observation is replaced by the next filtered value`() {
        // After the first fill (7 raw calls) the history holds {2,3,4,5,6}. One more raw
        // call (r=8) filters to r(7)=7 and evicts the oldest retained value, 2.
        val estimator = rampEstimator(capacity + MEDIAN_FILTER_WARMUP + 1)

        // New retained set {3,4,5,6,7}, mean 5.
        assertEquals(5.0, estimator.statistics!!.meanPower, 1e-12)
    }

    @Test
    fun `history stays bounded across multiple circular wrap-arounds`() {
        // capacity 5: 10 extra raw calls past the first fill is exactly two full wraps.
        val estimator = rampEstimator(capacity + MEDIAN_FILTER_WARMUP + 2 * capacity)

        assertEquals(capacity, estimator.statistics!!.sampleCount)
        // Retained set is the 5 most recent filtered outputs: r(12)..r(16) = {12..16}, mean 14.
        assertEquals(14.0, estimator.statistics!!.meanPower, 1e-12)
        assertEquals(sqrt(2.0), estimator.statistics!!.stdPower, 1e-12)
    }

    @Test
    fun `an isolated single-hop spike has no influence on the background estimate`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val baseline = 0.01
        val spike = 5.0

        estimator.addObservation(baseline)
        estimator.addObservation(baseline)
        estimator.addObservation(spike)
        repeat(capacity + MEDIAN_FILTER_WARMUP - 3) { estimator.addObservation(baseline) }

        assertTrue(estimator.isReady)
        assertEquals(baseline, estimator.statistics!!.meanPower, 1e-12)
        assertEquals(0.0, estimator.statistics!!.stdPower, 1e-12)
    }

    @Test
    fun `statistics remain finite and non-negative for a varied sequence with a spike`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val values = listOf(0.01, 0.03, 5.0, 0.02, 0.015, 0.04, 0.01, 0.05, 0.02, 0.03)

        for (value in values) estimator.addObservation(value)

        val stats = estimator.statistics!!
        assertTrue(stats.meanPower.isFinite())
        assertTrue(stats.meanPower >= 0.0)
        assertTrue(stats.stdPower.isFinite())
        assertTrue(stats.stdPower >= 0.0)
    }

    @Test
    fun `reset fully clears background state, including pending median filter state`() {
        val estimator = rampEstimator(capacity + MEDIAN_FILTER_WARMUP + 2 * capacity)
        assertTrue(estimator.isReady)

        estimator.reset()

        assertFalse(estimator.isReady)
        assertNull(estimator.statistics)

        // A fresh ramp starting from 1 again must reproduce exactly the first-fill result,
        // with no trace of the raw or pending values seen before reset.
        repeat(capacity + MEDIAN_FILTER_WARMUP) { estimator.addObservation((it + 1).toDouble()) }

        assertEquals(4.0, estimator.statistics!!.meanPower, 1e-12)
    }

    @Test
    fun `rejects a negative power observation`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        assertThrows(IllegalArgumentException::class.java) { estimator.addObservation(-0.1) }
    }

    @Test
    fun `rejects a non-finite power observation`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        assertThrows(IllegalArgumentException::class.java) { estimator.addObservation(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            estimator.addObservation(Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            estimator.addObservation(Double.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `a rejected observation leaves background state completely unaffected`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        estimator.addObservation(1.0)
        estimator.addObservation(2.0)

        assertThrows(IllegalArgumentException::class.java) { estimator.addObservation(Double.NaN) }

        // Continuing the ramp as if the rejected call never happened must reproduce
        // exactly the same first-fill result as the plain ramp scenario.
        for (i in 3..(capacity + MEDIAN_FILTER_WARMUP)) {
            estimator.addObservation(i.toDouble())
        }

        assertEquals(4.0, estimator.statistics!!.meanPower, 1e-12)
    }

    private companion object {
        const val MEDIAN_FILTER_WARMUP = 2
    }
}
