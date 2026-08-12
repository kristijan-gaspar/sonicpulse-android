package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThresholdEvaluatorTest {

    @Test
    fun `default multiplier is 5_0`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertEquals(5.0, evaluator.thresholdStdMultiplier, 0.0)
    }

    @Test
    fun `calculates threshold as mean plus K times standard deviation`() {
        val evaluator = AdaptiveThresholdEvaluator(thresholdStdMultiplier = 5.0)
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)

        val threshold = evaluator.calculateThreshold(stats)

        assertEquals(0.01 + 5.0 * 0.002, threshold, 1e-12)
    }

    @Test
    fun `uses a configurable multiplier`() {
        val evaluator = AdaptiveThresholdEvaluator(thresholdStdMultiplier = 3.0)
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)

        val threshold = evaluator.calculateThreshold(stats)

        assertEquals(0.01 + 3.0 * 0.002, threshold, 1e-12)
    }

    @Test
    fun `evaluates power strictly greater than threshold as true`() {
        val evaluator = AdaptiveThresholdEvaluator(thresholdStdMultiplier = 5.0)
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)
        val threshold = evaluator.calculateThreshold(stats)

        assertTrue(evaluator.exceedsThreshold(threshold + 1e-9, stats))
    }

    @Test
    fun `evaluates power below threshold as false`() {
        val evaluator = AdaptiveThresholdEvaluator(thresholdStdMultiplier = 5.0)
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)
        val threshold = evaluator.calculateThreshold(stats)

        assertFalse(evaluator.exceedsThreshold(threshold - 1e-9, stats))
    }

    @Test
    fun `evaluates power exactly equal to threshold as false`() {
        val evaluator = AdaptiveThresholdEvaluator(thresholdStdMultiplier = 5.0)
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)
        val threshold = evaluator.calculateThreshold(stats)

        assertFalse(evaluator.exceedsThreshold(threshold, stats))
    }

    @Test
    fun `rejects a non-positive multiplier`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveThresholdEvaluator(thresholdStdMultiplier = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveThresholdEvaluator(thresholdStdMultiplier = -1.0)
        }
    }

    @Test
    fun `rejects a negative current power`() {
        val evaluator = AdaptiveThresholdEvaluator()
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.exceedsThreshold(-0.1, stats)
        }
    }

    @Test
    fun `rejects a non-finite current power`() {
        val evaluator = AdaptiveThresholdEvaluator()
        val stats = BackgroundStatistics(meanPower = 0.01, stdPower = 0.002, sampleCount = 215)

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.exceedsThreshold(Double.NaN, stats)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator.exceedsThreshold(Double.POSITIVE_INFINITY, stats)
        }
    }
}
