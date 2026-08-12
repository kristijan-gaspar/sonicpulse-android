package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the processing order required by the plan: evaluating the current power
 * against the background must be side-effect free, and background history only
 * changes when the caller explicitly adds an observation, as a separate step.
 */
class AdaptiveBackgroundThresholdCausalityTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        backgroundHistoryMillis = 500
    )
    private val capacity = config.backgroundHistoryCapacity

    @Test
    fun `evaluating and thresholding a much larger power does not mutate background until explicitly added`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val evaluator = AdaptiveThresholdEvaluator()
        val th = 0.001
        val baseline = 0.01
        repeat(capacity) { estimator.addObservation(baseline) }
        assertTrue(estimator.isReady)

        val statisticsBefore = estimator.statistics!!
        val thresholdBefore = evaluator.calculateThreshold(statisticsBefore.medianPower, th)

        val currentPower = thresholdBefore * 10.0
        val exceeded = evaluator.exceedsThreshold(currentPower, statisticsBefore.medianPower, th)
        assertTrue(exceeded)

        val statisticsAfterEvaluation = estimator.statistics!!
        assertEquals(statisticsBefore.medianPower, statisticsAfterEvaluation.medianPower, 0.0)
        assertEquals(statisticsBefore.stdPower, statisticsAfterEvaluation.stdPower, 0.0)
        assertEquals(
            thresholdBefore,
            evaluator.calculateThreshold(statisticsAfterEvaluation.medianPower, th),
            0.0
        )

        // A single addObservation call replaces only the oldest of `capacity` retained
        // values; with capacity 5 the large value must become the retained majority
        // (more than half) before it can move the median, so repeat past that point.
        repeat(capacity / 2 + 1) { estimator.addObservation(currentPower) }

        val statisticsAfterAdd = estimator.statistics!!
        assertTrue(
            "explicitly adding the large observation enough times must change the background median",
            statisticsAfterAdd.medianPower != statisticsBefore.medianPower
        )
    }
}
