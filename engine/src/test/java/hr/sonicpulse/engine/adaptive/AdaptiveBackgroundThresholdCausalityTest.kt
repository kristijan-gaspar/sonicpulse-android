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

    // Median-of-3 warm-up: see AdaptiveBackgroundEstimatorTest.
    private val rawCallsToFill = config.backgroundHistoryCapacity + 2

    @Test
    fun `evaluating and thresholding a much larger power does not mutate background until explicitly added`() {
        val estimator = AdaptiveBackgroundEstimator(config)
        val evaluator = AdaptiveThresholdEvaluator()
        val baseline = 0.01
        repeat(rawCallsToFill) { estimator.addObservation(baseline) }
        assertTrue(estimator.isReady)

        val statisticsBefore = estimator.statistics!!
        val thresholdBefore = evaluator.calculateThreshold(statisticsBefore)

        val currentPower = thresholdBefore * 10.0
        val exceeded = evaluator.exceedsThreshold(currentPower, statisticsBefore)
        assertTrue(exceeded)

        val statisticsAfterEvaluation = estimator.statistics!!
        assertEquals(statisticsBefore.meanPower, statisticsAfterEvaluation.meanPower, 0.0)
        assertEquals(statisticsBefore.stdPower, statisticsAfterEvaluation.stdPower, 0.0)
        assertEquals(
            thresholdBefore,
            evaluator.calculateThreshold(statisticsAfterEvaluation),
            0.0
        )

        // A single addObservation call only queues the value into the median-of-3
        // filter; because it's still a minority value in that window, it does not yet
        // reach the retained history (matching the isolated-spike robustness behavior
        // verified in AdaptiveBackgroundEstimatorTest). Adding it three times in a row
        // makes it the majority value in the window, so it is explicitly admitted.
        repeat(3) { estimator.addObservation(currentPower) }

        val statisticsAfterAdd = estimator.statistics!!
        assertTrue(
            "explicitly adding the large observation enough times must change the background mean",
            statisticsAfterAdd.meanPower != statisticsBefore.meanPower
        )
    }
}
