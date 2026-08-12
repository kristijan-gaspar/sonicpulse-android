package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustVariationThresholdHistoryTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        variationHistoryMillis = 500,
        ov = 2.0
    )
    private val capacity = config.variationHistoryCapacity

    @Test
    fun `derived test capacity D is 5`() {
        assertEquals(5, capacity)
    }

    @Test
    fun `is not ready before D variations have been added`() {
        val history = RobustVariationThresholdHistory(config)

        repeat(capacity - 1) { history.addVariation(1.0) }

        assertFalse(history.isReady)
        assertNull(history.threshold)
    }

    @Test
    fun `is ready exactly when D variations have been added`() {
        val history = RobustVariationThresholdHistory(config)

        repeat(capacity - 1) { history.addVariation(1.0) }
        assertFalse(history.isReady)

        history.addVariation(1.0)

        assertTrue(history.isReady)
    }

    @Test
    fun `th equals ov times the max of the retained variations`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(1.0, 5.0, 3.0, 2.0, 4.0)) history.addVariation(value)

        // ov = 2.0, max = 5.0.
        assertEquals(10.0, history.threshold!!, 1e-12)
    }

    @Test
    fun `oldest variation is evicted so the rolling maximum uses exactly the last D values`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(100.0, 1.0, 2.0, 3.0, 4.0)) history.addVariation(value)
        assertEquals(200.0, history.threshold!!, 1e-12) // ov=2.0 * max(100)=200

        history.addVariation(5.0) // evicts the oldest value, 100.0

        assertEquals(10.0, history.threshold!!, 1e-12) // ov=2.0 * max(1,2,3,4,5)=5 -> 10
    }

    @Test
    fun `thresholdIncluding reflects a candidate without mutating the history`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) history.addVariation(value)
        val before = history.threshold!!

        val including = history.thresholdIncluding(candidateVariation = 100.0)

        assertEquals(200.0, including, 1e-12) // ov=2.0 * max(1..5,100)=100 -> 200
        assertEquals(before, history.threshold!!, 0.0) // unchanged: no mutation occurred
    }

    @Test
    fun `thresholdIncluding a small candidate does not lower the threshold below the retained max`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) history.addVariation(value)

        val including = history.thresholdIncluding(candidateVariation = 0.0)

        assertEquals(10.0, including, 1e-12) // ov=2.0 * max(1..5,0)=5 -> 10
    }

    @Test
    fun `reset fully clears robust threshold state`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(1.0, 2.0, 3.0, 4.0, 100.0)) history.addVariation(value)
        assertTrue(history.isReady)

        history.reset()

        assertFalse(history.isReady)
        assertNull(history.threshold)

        for (value in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) history.addVariation(value)
        // No trace of the pre-reset 100.0 value.
        assertEquals(10.0, history.threshold!!, 1e-12)
    }

    @Test
    fun `rejects a non-finite variation`() {
        val history = RobustVariationThresholdHistory(config)

        assertThrows(IllegalArgumentException::class.java) { history.addVariation(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            history.addVariation(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `rejects a non-finite thresholdIncluding candidate`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) history.addVariation(value)

        assertThrows(IllegalArgumentException::class.java) {
            history.thresholdIncluding(Double.NaN)
        }
    }

    @Test
    fun `a negative variation is accepted since variation can legitimately be negative`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(-5.0, -3.0, -1.0, -4.0, -2.0)) history.addVariation(value)

        assertEquals(-2.0, history.threshold!!, 1e-12) // ov=2.0 * max(-5..-1)=-1 -> -2.0
    }
}
