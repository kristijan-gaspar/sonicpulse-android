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
        thresholdStdMultiplier = 5.0,
        ov = 2.0
    )

    private fun setup(): Triple<AdaptiveBackgroundEstimator, RobustVariationThresholdHistory, AdaptiveRobustThresholdCalculator> {
        val background = AdaptiveBackgroundEstimator(config)
        val variationHistory = RobustVariationThresholdHistory(config)
        val evaluator = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
        return Triple(background, variationHistory, AdaptiveRobustThresholdCalculator(background, variationHistory, evaluator))
    }

    @Test
    fun `returns null before background history is ready`() {
        val (background, _, calculator) = setup()
        repeat(config.backgroundHistoryCapacity - 1) { background.addObservation(0.01) }

        assertNull(calculator.evaluate(0.5))
    }

    @Test
    fun `bootstrap phase falls back exactly to the classic K times stdPower threshold`() {
        val (background, variationHistory, calculator) = setup()
        // Chronological oldest->newest. mfa=1.0 (median), stdPower=3.6 (see AdaptiveBackgroundEstimatorTest).
        for (value in listOf(1.0, 1.0, 1.0, 1.0, 10.0)) background.addObservation(value)
        assertFalse(variationHistory.isReady)

        val result = calculator.evaluate(currentPower = 0.5)!!

        assertTrue(result.isBootstrapping)
        val classicEvaluator = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
        val classicThreshold = classicEvaluator.calculateThreshold(background.statistics!!)
        assertEquals(classicThreshold, result.threshold, 1e-12)
        assertEquals(1.0, result.mfa, 1e-12)
        assertEquals(18.0, result.th, 1e-12) // 5.0 * 3.6
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

        val classicEvaluator = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
        val classicThreshold = classicEvaluator.calculateThreshold(background.statistics!!)
        assertTrue(
            "robust threshold must differ from the classic K*std threshold once ready",
            result.threshold != classicThreshold
        )
    }

    @Test
    fun `conditional median uses the previous th, not the final threshold T`() {
        val (background, variationHistory, calculator) = setup()
        // Chronological oldest->newest: sorted median mfa=10.0; e(k-d) (index 3) = 17.0.
        for (value in listOf(10.0, 10.0, 10.0, 17.0, 10.0)) background.addObservation(value)
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
        for (value in listOf(10.0, 10.0, 10.0, 17.0, 10.0)) background.addObservation(value)
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

        val evaluatorA = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
        val evaluatorB = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
        val calculatorA = AdaptiveRobustThresholdCalculator(backgroundA, variationHistoryA, evaluatorA)
        val calculatorB = AdaptiveRobustThresholdCalculator(backgroundB, variationHistoryB, evaluatorB)

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
}
