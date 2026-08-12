package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RobustThresholdEvaluationTest {

    @Test
    fun `stores all given fields`() {
        val result = RobustThresholdEvaluation(
            mfa = 0.02,
            th = 0.03,
            variation = 0.01,
            threshold = 0.05,
            exceedsThreshold = true,
            isBootstrapping = false
        )

        assertEquals(0.02, result.mfa, 0.0)
        assertEquals(0.03, result.th, 0.0)
        assertEquals(0.01, result.variation, 0.0)
        assertEquals(0.05, result.threshold, 0.0)
        assertEquals(true, result.exceedsThreshold)
        assertEquals(false, result.isBootstrapping)
    }

    @Test
    fun `th and variation may be negative`() {
        val result = RobustThresholdEvaluation(
            mfa = 0.02,
            th = -0.01,
            variation = -0.01,
            threshold = 0.01,
            exceedsThreshold = false,
            isBootstrapping = true
        )

        assertEquals(-0.01, result.th, 0.0)
        assertEquals(-0.01, result.variation, 0.0)
    }

    @Test
    fun `rejects a negative mfa`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustThresholdEvaluation(
                mfa = -0.1,
                th = 0.0,
                variation = 0.0,
                threshold = -0.1,
                exceedsThreshold = false,
                isBootstrapping = false
            )
        }
    }

    @Test
    fun `rejects a non-finite mfa, th, variation or threshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustThresholdEvaluation(Double.NaN, 0.0, 0.0, 0.0, false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RobustThresholdEvaluation(0.0, Double.NaN, 0.0, 0.0, false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RobustThresholdEvaluation(0.0, 0.0, Double.NaN, 0.0, false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RobustThresholdEvaluation(0.0, 0.0, 0.0, Double.NaN, false, false)
        }
    }
}
