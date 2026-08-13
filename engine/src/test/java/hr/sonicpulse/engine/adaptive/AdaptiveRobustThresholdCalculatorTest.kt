package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRobustThresholdCalculatorTest {

    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        backgroundHistoryMillis = 500,
        variationHistoryMillis = 500,
        // Matched to variationHistoryMillis so isReady flips exactly after D=5 admissions,
        // same as the pre-decoupling tests below assume.
        variationWarmupMillis = 500,
        initialThaStdMultiplier = 5.0,
        ov = 2.0
    )

    private fun setup(): Triple<AdaptiveBackgroundEstimator, RobustVariationThresholdHistory, AdaptiveRobustThresholdCalculator> {
        val background = AdaptiveBackgroundEstimator(config)
        val variationHistory = RobustVariationThresholdHistory(config)
        val evaluator = AdaptiveThresholdEvaluator()
        return Triple(
            background,
            variationHistory,
            AdaptiveRobustThresholdCalculator(config, background, variationHistory, evaluator)
        )
    }

    @Test
    fun `returns null before background history is ready`() {
        val (background, _, calculator) = setup()
        repeat(config.backgroundHistoryCapacity - 1) { background.addObservation(0.01) }

        assertNull(calculator.evaluate(0.5))
    }

    @Test
    fun `tha before any variation exists equals initialThaStdMultiplier times stdPower`() {
        val (background, variationHistory, calculator) = setup()
        // Chronological oldest->newest. mfa=1.0 (median), stdPower=3.6 (see AdaptiveBackgroundEstimatorTest).
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        assertNull(variationHistory.threshold) // no variation admitted yet -> tha must use the std seed
        val stats = background.statistics!!
        val expectedTha = config.initialThaStdMultiplier * stats.stdPower
        val expectedVariation = background.robustVariation(conditionalMedianThreshold = expectedTha)!!.difference

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertTrue(result.isBootstrapping)
        assertEquals(expectedVariation, result.variation, 0.0)
    }

    @Test
    fun `th during bootstrap goes through Eq 3_9, never falls back directly to K times stdPower`() {
        val (background, _, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)

        val result = calculator.evaluate(currentPower = 0.5)!!

        // e(k-d)=1.0, mfa=1.0 -> variation=0.0; th = ov * max(variation) = ov*0.0 = 0.0. If th
        // had wrongly used the K*stdPower seed (18.0) directly instead of routing through
        // Eq 3.9's thresholdIncluding, it would be 18.0, not 0.0.
        assertEquals(0.0, result.variation, 1e-12)
        assertEquals(0.0, result.th, 1e-12)
        assertEquals(1.0, result.threshold, 1e-12) // mfa(1.0) + th(0.0)
    }

    @Test
    fun `once variation warm-up completes, tha comes from the previous Eq 3_9 threshold, not stdPower again`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        // variationWarmupCapacity is 5 for this config (matched to D): admit exactly that
        // many variations to reach isReady - a single variation is NOT enough (see the
        // dedicated bootstrap-persistence test below for that distinction).
        for (value in listOf(0.5, 0.5, 0.5, 0.5, 0.5)) variationHistory.addVariation(value)
        assertTrue(variationHistory.isReady)
        val previousTh = variationHistory.threshold!!
        val stats = background.statistics!!
        val stdSeed = config.initialThaStdMultiplier * stats.stdPower
        assertTrue(previousTh != stdSeed)
        val expectedVariation = background.robustVariation(conditionalMedianThreshold = previousTh)!!.difference

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertEquals(expectedVariation, result.variation, 0.0)
    }

    @Test
    fun `tha keeps using the std seed for the entire warm-up window, not just before the first variation`() {
        // D=10, warmup=3 (decoupled): with the bug this test guards against, tha would
        // incorrectly switch to variationHistory.threshold as soon as it becomes non-null
        // (after the first admitted variation), well before isReady - risking the
        // variation(1)=0 -> th=0 -> tha(2)=0 -> ... collapse described in the task.
        val decoupledConfig = config.copy(variationHistoryMillis = 1000, variationWarmupMillis = 300)
        val background = AdaptiveBackgroundEstimator(decoupledConfig)
        val variationHistory = RobustVariationThresholdHistory(decoupledConfig)
        val evaluator = AdaptiveThresholdEvaluator()
        val calculator = AdaptiveRobustThresholdCalculator(decoupledConfig, background, variationHistory, evaluator)
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        // 2 variations admitted (threshold already non-null, per the fixed getter) but still
        // short of variationWarmupCapacity (3) - isReady must still be false.
        variationHistory.addVariation(0.2)
        variationHistory.addVariation(0.2)
        assertFalse(variationHistory.isReady)
        assertEquals(0.4, variationHistory.threshold!!, 1e-12) // already non-null: ov=2.0 * max(0.2,0.2)

        val stats = background.statistics!!
        val expectedTha = decoupledConfig.initialThaStdMultiplier * stats.stdPower
        val expectedVariation = background.robustVariation(conditionalMedianThreshold = expectedTha)!!.difference

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertTrue(result.isBootstrapping)
        assertEquals(expectedVariation, result.variation, 0.0)
    }

    @Test
    fun `once the variation history is ready, th and threshold use Eq 3_9, not stdPower`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        for (value in listOf(0.1, 0.2, 0.3, 0.4, 0.5)) variationHistory.addVariation(value)
        assertTrue(variationHistory.isReady)

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertFalse(result.isBootstrapping)
        // e(k-d) = delayed chronological sample = 1.0 (see AdaptiveBackgroundEstimatorTest
        // for the same background sequence), mfa = 1.0, tha = th(k-1) = 2.0*0.5 = 1.0.
        // |1.0-1.0|=0 <= 1.0 -> not suppressed -> cmfa = 1.0 -> variation = 0.0.
        assertEquals(0.0, result.variation, 1e-12)
        assertEquals(1.0, result.th, 1e-12) // ov=2.0 * max(0.1..0.5, 0.0) = 2.0*0.5
        assertEquals(2.0, result.threshold, 1e-12) // mfa(1.0) + th(1.0)
    }

    @Test
    fun `conditional median uses the previous th, not the final threshold T`() {
        val (background, variationHistory, calculator) = setup()
        // Chronological oldest->newest: sorted median mfa=10.0; e(k-d) (index 2) = 17.0.
        for (value in listOf(10.0, 10.0, 17.0, 10.0, 10.0)) background.addObservation(value)
        // D-window ready with max 1.0 -> th(k-1) = ov*1.0 = 2.0. T(k-1) would be mfa+th = 12.0.
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 1.0)) variationHistory.addVariation(value)

        val result = calculator.evaluate(currentPower = 0.5)!!

        // deviation |17-10|=7: exceeds correct tha=2.0 (suppressed, variation=0), but would
        // NOT exceed the wrong tha=T=12.0 (which would leave variation=17-10=7.0).
        assertEquals(0.0, result.variation, 1e-12)
    }

    @Test
    fun `variation is exactly conditionalMedianPower minus medianPower`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(10.0, 10.0, 17.0, 10.0, 10.0)) background.addObservation(value)
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 1.0)) variationHistory.addVariation(value)

        val result = calculator.evaluate(currentPower = 0.5)!!
        val direct = background.robustVariation(conditionalMedianThreshold = variationHistory.threshold!!)!!

        assertEquals(direct.difference, result.variation, 0.0)
    }

    @Test
    fun `threshold is exactly mfa plus th`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        for (value in listOf(0.1, 0.2, 0.3, 0.4, 0.5)) variationHistory.addVariation(value)

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertEquals(result.mfa + result.th, result.threshold, 0.0)
    }

    @Test
    fun `strict trigger - equal to threshold does not exceed`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        for (value in listOf(0.1, 0.2, 0.3, 0.4, 0.5)) variationHistory.addVariation(value)
        val threshold = calculator.evaluate(currentPower = 0.0)!!.threshold

        assertFalse(calculator.evaluate(threshold)!!.exceedsThreshold)
        assertTrue(calculator.evaluate(threshold + 1e-9)!!.exceedsThreshold)
        assertFalse(calculator.evaluate(threshold - 1e-9)!!.exceedsThreshold)
    }

    @Test
    fun `after readiness, changing stdPower alone does not change th or threshold`() {
        // Same median (5.0), different spread (different stdPower).
        val backgroundA = AdaptiveBackgroundEstimator(config)
        for (value in listOf(5.0, 5.0, 5.0, 5.0, 5.0)) backgroundA.addObservation(value) // std=0
        val backgroundB = AdaptiveBackgroundEstimator(config)
        for (value in listOf(3.0, 4.0, 5.0, 6.0, 7.0)) backgroundB.addObservation(value) // std=sqrt(2)
        assertTrue(backgroundA.statistics!!.stdPower != backgroundB.statistics!!.stdPower)
        assertEquals(backgroundA.statistics!!.medianPower, backgroundB.statistics!!.medianPower, 0.0)

        // Identical, dominant D-window in both, so any small variation stays below its max.
        val variationHistoryA = RobustVariationThresholdHistory(config)
        val variationHistoryB = RobustVariationThresholdHistory(config)
        for (value in listOf(50.0, 50.0, 50.0, 50.0, 50.0)) {
            variationHistoryA.addVariation(value)
            variationHistoryB.addVariation(value)
        }

        val evaluatorA = AdaptiveThresholdEvaluator()
        val evaluatorB = AdaptiveThresholdEvaluator()
        val calculatorA = AdaptiveRobustThresholdCalculator(config, backgroundA, variationHistoryA, evaluatorA)
        val calculatorB = AdaptiveRobustThresholdCalculator(config, backgroundB, variationHistoryB, evaluatorB)

        val resultA = calculatorA.evaluate(currentPower = 1.0)!!
        val resultB = calculatorB.evaluate(currentPower = 1.0)!!

        assertEquals(resultA.th, resultB.th, 1e-12)
        assertEquals(resultA.threshold, resultB.threshold, 1e-12)
    }

    @Test
    fun `evaluate does not mutate background history or variation history`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        for (value in listOf(0.1, 0.2, 0.3, 0.4, 0.5)) variationHistory.addVariation(value)
        val statisticsBefore = background.statistics!!
        val thresholdBefore = variationHistory.threshold!!

        calculator.evaluate(currentPower = 0.5)
        calculator.evaluate(currentPower = 999.0)

        assertEquals(statisticsBefore, background.statistics!!)
        assertEquals(thresholdBefore, variationHistory.threshold!!, 0.0)
    }

    @Test
    fun `no current-sample self-contamination - only exceedsThreshold changes with currentPower`() {
        val (background, variationHistory, calculator) = setup()
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        for (value in listOf(0.1, 0.2, 0.3, 0.4, 0.5)) variationHistory.addVariation(value)

        val small = calculator.evaluate(currentPower = 0.001)!!
        val large = calculator.evaluate(currentPower = 1000.0)!!

        assertEquals(small.mfa, large.mfa, 0.0)
        assertEquals(small.th, large.th, 0.0)
        assertEquals(small.variation, large.variation, 0.0)
        assertEquals(small.threshold, large.threshold, 0.0)
        assertFalse(small.exceedsThreshold)
        assertTrue(large.exceedsThreshold)
    }

    @Test
    fun `after readiness a zero previous th uses the std seed to recover`() {
        val (background, variationHistory, calculator) = setup()

        // mfa = 10.0, delayed reference e(k-d) = 17.0.
        // The non-zero spread gives a non-zero std-based recovery tha.
        for (value in listOf(10.0, 10.0, 17.0, 10.0, 10.0)) {
            background.addObservation(value)
        }

        // Make the variation history ready, but with an effective previous th of exactly 0.
        repeat(config.variationWarmupCapacity) {
            variationHistory.addVariation(0.0)
        }

        assertTrue(variationHistory.isReady)
        assertEquals(0.0, variationHistory.threshold!!, 0.0)

        val statistics = background.statistics!!
        val recoveryTha =
            config.initialThaStdMultiplier * statistics.stdPower

        val expectedVariation =
            background.robustVariation(
                conditionalMedianThreshold = recoveryTha
            )!!.difference

        // With tha=0 this would collapse to variation=0.
        // With the std-based recovery seed, the delayed reference survives here.
        assertEquals(7.0, expectedVariation, 1e-12)

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertFalse(result.isBootstrapping)
        assertEquals(expectedVariation, result.variation, 1e-12)
        assertEquals(config.ov * expectedVariation, result.th, 1e-12)
    }
}
