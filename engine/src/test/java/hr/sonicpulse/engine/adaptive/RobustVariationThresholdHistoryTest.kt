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
        // Matched to variationHistoryMillis so isReady flips exactly at the full D window,
        // same as pre-decoupling behavior - see the dedicated decoupledConfig tests below
        // for isReady/full-window independence.
        variationWarmupMillis = 500,
        ov = 2.0
    )
    private val capacity = config.variationHistoryCapacity

    // D = 10 hops, warmup = 3 hops: deliberately mismatched, to prove isReady (warmup-gated)
    // and FIFO eviction (full-D-gated) are independent.
    private val decoupledConfig = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        variationHistoryMillis = 1000,
        variationWarmupMillis = 300,
        ov = 2.0
    )

    @Test
    fun `derived test capacity D is 5`() {
        assertEquals(5, capacity)
    }

    @Test
    fun `is not ready before D variations have been added`() {
        val history = RobustVariationThresholdHistory(config)

        repeat(capacity - 1) { history.addVariation(1.0) }

        assertFalse(history.isReady)
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
    fun `reading threshold from a full buffer includes ALL currently retained D values, not D-1`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(100.0, 1.0, 2.0, 3.0, 4.0)) history.addVariation(value) // exactly full, D=5
        assertTrue(history.isReady)

        // A plain read must never simulate an eviction that hasn't actually happened - all 5
        // currently retained values count, including the oldest (100.0), which is still
        // physically retained until a further addVariation() actually evicts it.
        assertEquals(200.0, history.threshold!!, 1e-12) // ov=2.0 * max(100,1,2,3,4)=100 -> 200
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
    fun `thresholdIncluding excludes the oldest retained value once full, not just adds the candidate`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(100.0, 1.0, 2.0, 3.0, 4.0)) history.addVariation(value)
        assertTrue(history.isReady)

        // Admitting candidate=5 for real would evict the oldest retained value, 100.0
        // (FIFO), leaving {1,2,3,4,5}. The candidate-inclusive max must therefore be 5,
        // not 100 - the bug was including all D retained values (D+1 total) instead of
        // the newest D-1 plus the candidate.
        val including = history.thresholdIncluding(candidateVariation = 5.0)

        assertEquals(10.0, including, 1e-12) // ov=2.0 * max(1,2,3,4,5)=5 -> 10, not ov*100=200
    }

    @Test
    fun `thresholdIncluding matches the threshold obtained by actually adding the same candidate`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(100.0, 1.0, 2.0, 3.0, 4.0)) history.addVariation(value)

        val including = history.thresholdIncluding(candidateVariation = 5.0)

        history.addVariation(5.0)
        assertEquals(including, history.threshold!!, 0.0)
    }

    @Test
    fun `thresholdIncluding does not exclude anything while the history is still filling`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(100.0, 1.0, 2.0, 3.0)) history.addVariation(value) // capacity-1, not ready
        assertFalse(history.isReady)

        // Not full yet: admitting the candidate for real would not evict anything, so the
        // candidate-inclusive max must still consider all currently retained values.
        val including = history.thresholdIncluding(candidateVariation = 5.0)

        assertEquals(200.0, including, 1e-12) // ov=2.0 * max(100,1,2,3,5)=100 -> 200
    }

    @Test
    fun `a negative variation is accepted since variation can legitimately be negative`() {
        val history = RobustVariationThresholdHistory(config)
        for (value in listOf(-5.0, -3.0, -1.0, -4.0, -2.0)) history.addVariation(value)

        assertEquals(-2.0, history.threshold!!, 1e-12) // ov=2.0 * max(-5..-1)=-1 -> -2.0
    }

    @Test
    fun `threshold is non-null as soon as a single variation is added, well before isReady`() {
        val history = RobustVariationThresholdHistory(decoupledConfig)

        history.addVariation(4.0)

        assertFalse(history.isReady) // warmup capacity is 3, only 1 admitted so far
        assertEquals(8.0, history.threshold!!, 1e-12) // ov=2.0 * max(4.0)=8.0
    }

    @Test
    fun `isReady switches after variationWarmupCapacity, independent of the full D window`() {
        val history = RobustVariationThresholdHistory(decoupledConfig)

        repeat(2) { history.addVariation(1.0) }
        assertFalse(history.isReady)

        history.addVariation(1.0) // 3rd addition -> warmup capacity (3) reached

        assertTrue(history.isReady)
    }

    @Test
    fun `history keeps growing without eviction past isReady, until the full D window fills`() {
        val history = RobustVariationThresholdHistory(decoupledConfig)

        for (value in listOf(9.0, 1.0, 1.0)) history.addVariation(value) // isReady flips here
        assertTrue(history.isReady)

        // Still below the full D=10 capacity: no eviction yet, so the early 9.0 still dominates.
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)) history.addVariation(value) // 9 total
        assertEquals(18.0, history.threshold!!, 1e-12) // ov=2.0 * max(9.0, 1.0...)=18.0

        history.addVariation(1.0) // 10th addition -> D window now exactly full, still no eviction
        assertEquals(18.0, history.threshold!!, 1e-12)

        history.addVariation(2.0) // 11th addition -> evicts the oldest retained value, 9.0
        assertEquals(4.0, history.threshold!!, 1e-12) // ov=2.0 * max(1.0..1.0, 2.0)=4.0
    }
}
