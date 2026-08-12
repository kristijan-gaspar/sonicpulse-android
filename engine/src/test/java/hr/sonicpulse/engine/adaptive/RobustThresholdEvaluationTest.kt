package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RobustThresholdEvaluationTest {

    private fun build(
        mfa: Double = 0.02,
        stdPower: Double = 0.005,
        tha: Double = 0.01,
        cmfa: Double = 0.02,
        th: Double = 0.03,
        variation: Double = 0.01,
        threshold: Double = 0.05,
        exceedsThreshold: Boolean = true,
        isBootstrapping: Boolean = false
    ) = RobustThresholdEvaluation(mfa, stdPower, tha, cmfa, th, variation, threshold, exceedsThreshold, isBootstrapping)

    @Test
    fun `stores all given fields`() {
        val result = build()

        assertEquals(0.02, result.mfa, 0.0)
        assertEquals(0.005, result.stdPower, 0.0)
        assertEquals(0.01, result.tha, 0.0)
        assertEquals(0.02, result.cmfa, 0.0)
        assertEquals(0.03, result.th, 0.0)
        assertEquals(0.01, result.variation, 0.0)
        assertEquals(0.05, result.threshold, 0.0)
        assertEquals(true, result.exceedsThreshold)
        assertEquals(false, result.isBootstrapping)
    }

    @Test
    fun `th and variation may be negative`() {
        val result = build(th = -0.01, variation = -0.01, threshold = 0.01, exceedsThreshold = false, isBootstrapping = true)

        assertEquals(-0.01, result.th, 0.0)
        assertEquals(-0.01, result.variation, 0.0)
    }

    @Test
    fun `rejects a negative mfa, stdPower, tha or cmfa`() {
        assertThrows(IllegalArgumentException::class.java) { build(mfa = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { build(stdPower = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { build(tha = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { build(cmfa = -0.1) }
    }

    @Test
    fun `rejects a non-finite mfa, stdPower, tha, cmfa, th, variation or threshold`() {
        assertThrows(IllegalArgumentException::class.java) { build(mfa = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(stdPower = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(tha = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(cmfa = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(th = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(variation = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { build(threshold = Double.NaN) }
    }
}
