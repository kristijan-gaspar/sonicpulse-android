package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThresholdEvaluatorTest {

    @Test
    fun `calculateThreshold is exactly mfa plus th`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertEquals(0.05, evaluator.calculateThreshold(mfa = 0.02, th = 0.03), 1e-12)
    }

    @Test
    fun `calculateThreshold accepts a negative th`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertEquals(0.01, evaluator.calculateThreshold(mfa = 0.02, th = -0.01), 1e-12)
    }

    @Test
    fun `rejects a negative mfa`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.calculateThreshold(mfa = -0.01, th = 0.0)
        }
    }

    @Test
    fun `rejects a non-finite mfa or th`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.calculateThreshold(mfa = Double.NaN, th = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator.calculateThreshold(mfa = 0.0, th = Double.NaN)
        }
    }

    @Test
    fun `exceedsThreshold is strict - equal does not trigger`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertFalse(evaluator.exceedsThreshold(currentPower = 0.05, mfa = 0.02, th = 0.03))
        assertTrue(evaluator.exceedsThreshold(currentPower = 0.05 + 1e-9, mfa = 0.02, th = 0.03))
        assertFalse(evaluator.exceedsThreshold(currentPower = 0.05 - 1e-9, mfa = 0.02, th = 0.03))
    }

    @Test
    fun `exceedsThreshold rejects a negative or non-finite current power`() {
        val evaluator = AdaptiveThresholdEvaluator()

        assertThrows(IllegalArgumentException::class.java) {
            evaluator.exceedsThreshold(-0.1, mfa = 0.02, th = 0.03)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator.exceedsThreshold(Double.NaN, mfa = 0.02, th = 0.03)
        }
    }
}
