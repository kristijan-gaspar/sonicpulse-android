package hr.sonicpulse.engine.adaptive

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBackgroundEstimatorTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        backgroundHistoryMillis = 500
    )
    private val capacity = config.backgroundHistoryCapacity

    private val evenConfig = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        backgroundHistoryMillis = 400
    )
    private val evenCapacity = evenConfig.backgroundHistoryCapacity

    @Test
    fun `derived test capacities are 5 (odd) and 4 (even)`() {
        assertEquals(5, capacity)
        assertEquals(4, evenCapacity)
    }

    @Test
    fun `is not ready before history is full`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity - 1) { estimator.addObservation(0.01) }

        assertFalse(estimator.isReady)
        assertNull(estimator.statistics)
    }

    @Test
    fun `is ready exactly when exactly capacity observations have been accepted`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity - 1) { estimator.addObservation(0.01) }
        assertFalse(estimator.isReady)

        estimator.addObservation(0.01)

        assertTrue(estimator.isReady)
        assertEquals(capacity, estimator.statistics!!.sampleCount)
    }

    private fun rampEstimator(config: AdaptiveEngineConfig, rawCallCount: Int): AdaptiveBackgroundEstimator {
        val estimator = AdaptiveBackgroundEstimator(config)
        for (i in 1..rawCallCount) {
            estimator.addObservation(i.toDouble())
        }
        return estimator
    }

    @Test
    fun `odd retained count uses the central sorted value as the median`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        for (value in listOf(5.0, 1.0, 4.0, 2.0, 3.0)) estimator.addObservation(value)

        // sorted {1,2,3,4,5}, capacity 5 -> index 5/2=2 -> 3.0.
        assertEquals(3.0, estimator.statistics!!.medianPower, 1e-12)
    }

    @Test
    fun `even retained count uses the higher of the two central sorted values`() {
        val estimator = AdaptiveBackgroundEstimator(evenConfig)
        for (value in listOf(4.0, 1.0, 3.0, 2.0)) estimator.addObservation(value)

        // sorted {1,2,3,4}, capacity 4 -> index 4/2=2 -> 3.0 (not the average of 2 and 3).
        assertEquals(3.0, estimator.statistics!!.medianPower, 1e-12)
    }

    @Test
    fun `oldest observation is replaced by the next accepted observation`() {
        val estimator = rampEstimator(config, capacity)
        // First fill {1,2,3,4,5}: median = sorted[2] = 3.

        estimator.addObservation(6.0)

        // New retained set {2,3,4,5,6}: median = sorted[2] = 4.
        assertEquals(4.0, estimator.statistics!!.medianPower, 1e-12)
    }

    @Test
    fun `history stays bounded and correct across multiple circular wrap-arounds`() {
        // capacity 5: 2 extra full wraps past the first fill is 10 extra observations.
        val estimator = rampEstimator(config, capacity + 2 * capacity)

        assertEquals(capacity, estimator.statistics!!.sampleCount)
        // Retained set is the 5 most recent values: {11,12,13,14,15}.
        assertEquals(13.0, estimator.statistics!!.medianPower, 1e-12)
        assertEquals(sqrt(2.0), estimator.statistics!!.stdPower, 1e-12)
    }

    @Test
    fun `sigma is the population standard deviation of the retained observations, independent of the median`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) estimator.addObservation(value)

        val stats = estimator.statistics!!
        // sorted {1,1,1,1,10}: median = sorted[2] = 1.0, but arithmeticMean = 2.8 and
        // population std = sqrt(((1-2.8)^2*4 + (10-2.8)^2) / 5) = 3.6.
        assertEquals(1.0, stats.medianPower, 1e-12)
        assertEquals(3.6, stats.stdPower, 1e-9)
    }

    @Test
    fun `one isolated high value in a sufficiently populated history does not dominate the median`() {
        val defaultConfig = AdaptiveEngineConfig()
        val estimator = AdaptiveBackgroundEstimator(defaultConfig)
        val baseline = 0.01
        val spike = 5.0
        val capacity = defaultConfig.backgroundHistoryCapacity

        repeat(capacity / 2) { estimator.addObservation(baseline) }
        estimator.addObservation(spike)
        repeat(capacity - capacity / 2 - 1) { estimator.addObservation(baseline) }

        assertTrue(estimator.isReady)
        assertEquals(baseline, estimator.statistics!!.medianPower, 1e-12)
        // The spike is still part of the retained history and does move sigma.
        assertTrue(estimator.statistics!!.stdPower > 0.0)
    }

    @Test
    fun `statistics remain finite and non-negative for a varied sequence with a spike`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val values = listOf(0.01, 0.03, 5.0, 0.02, 0.015)

        for (value in values) estimator.addObservation(value)

        val stats = estimator.statistics!!
        assertTrue(stats.medianPower.isFinite())
        assertTrue(stats.medianPower >= 0.0)
        assertTrue(stats.stdPower.isFinite())
        assertTrue(stats.stdPower >= 0.0)
    }

    @Test
    fun `reset fully clears background state`() {
        val estimator = rampEstimator(config, capacity + 2 * capacity)
        assertTrue(estimator.isReady)

        estimator.reset()

        assertFalse(estimator.isReady)
        assertNull(estimator.statistics)

        // A fresh ramp starting from 1 again must reproduce exactly the first-fill
        // result, with no trace of the values seen before reset.
        for (i in 1..capacity) estimator.addObservation(i.toDouble())

        assertEquals(3.0, estimator.statistics!!.medianPower, 1e-12)
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

        // Continuing as if the rejected call never happened must reproduce exactly the
        // same first-fill result as the plain ramp scenario.
        for (i in 3..capacity) estimator.addObservation(i.toDouble())

        assertEquals(3.0, estimator.statistics!!.medianPower, 1e-12)
    }

    @Test
    fun `robustVariation returns null before history is full`() {
        val estimator = AdaptiveBackgroundEstimator(config)

        repeat(capacity - 1) { estimator.addObservation(0.01) }

        assertNull(estimator.robustVariation(referencePower = 0.5, conditionalMedianThreshold = 0.1))
    }

    @Test
    fun `robustVariation medianPower matches statistics medianPower once ready`() {
        val estimator = rampEstimator(config, capacity)

        val variation = estimator.robustVariation(referencePower = 3.0, conditionalMedianThreshold = 1.0)!!

        assertEquals(estimator.statistics!!.medianPower, variation.medianPower, 0.0)
    }

    @Test
    fun `robustVariation suppresses an isolated spike used as the reference power`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val baseline = 0.01
        repeat(capacity) { estimator.addObservation(baseline) }

        val variation = estimator.robustVariation(referencePower = 5.0, conditionalMedianThreshold = 0.05)!!

        assertEquals(baseline, variation.medianPower, 1e-12)
        assertEquals(baseline, variation.conditionalMedianPower, 1e-12)
        assertEquals(0.0, variation.difference, 1e-12)
    }

    @Test
    fun `robustVariation does not mutate background state`() {
        val estimator = rampEstimator(config, capacity)
        val statisticsBefore = estimator.statistics!!

        estimator.robustVariation(referencePower = 100.0, conditionalMedianThreshold = 0.5)
        estimator.robustVariation(referencePower = 0.0, conditionalMedianThreshold = 0.5)

        val statisticsAfter = estimator.statistics!!
        assertEquals(statisticsBefore.medianPower, statisticsAfter.medianPower, 0.0)
        assertEquals(statisticsBefore.stdPower, statisticsAfter.stdPower, 0.0)
        assertEquals(statisticsBefore.sampleCount, statisticsAfter.sampleCount)
        assertTrue(estimator.isReady)
    }
}
