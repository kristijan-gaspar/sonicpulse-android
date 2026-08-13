package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConditionalMedianCalculatorTest {

    @Test
    fun `median of an odd-count window is the central sorted value`() {
        val window = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)

        assertEquals(3.0, ConditionalMedianCalculator.median(window), 1e-12)
    }

    @Test
    fun `median of an even-count window uses the higher of the two central sorted values`() {
        // Same convention as AdaptiveBackgroundEstimator: sorted[size/2].
        val window = doubleArrayOf(4.0, 1.0, 3.0, 2.0)

        assertEquals(3.0, ConditionalMedianCalculator.median(window), 1e-12)
    }

    @Test
    fun `median rejects an empty window`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionalMedianCalculator.median(doubleArrayOf())
        }
    }

    @Test
    fun `conditional median returns the reference value when deviation is within threshold`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0) // mf = 3.0

        val result = ConditionalMedianCalculator.evaluate(
            window = window,
            referencePower = 3.5,
            conditionalMedianThreshold = 1.0
        )

        // |3.5 - 3.0| = 0.5, does not exceed the threshold of 1.0.
        assertEquals(3.5, result.conditionalMedianPower, 1e-12)
    }

    @Test
    fun `conditional median returns the reference value when deviation exactly equals threshold`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0) // mf = 3.0

        val result = ConditionalMedianCalculator.evaluate(
            window = window,
            referencePower = 4.0,
            conditionalMedianThreshold = 1.0
        )

        // |4.0 - 3.0| = 1.0 does not exceed (strictly) a threshold of 1.0.
        assertEquals(4.0, result.conditionalMedianPower, 1e-12)
    }

    @Test
    fun `conditional median returns the median when deviation exceeds threshold`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0) // mf = 3.0

        val result = ConditionalMedianCalculator.evaluate(
            window = window,
            referencePower = 10.0,
            conditionalMedianThreshold = 1.0
        )

        // |10.0 - 3.0| = 7.0 exceeds the threshold of 1.0.
        assertEquals(3.0, result.conditionalMedianPower, 1e-12)
    }

    @Test
    fun `difference is exactly conditionalMedianPower minus medianPower, both branches`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0) // mf = 3.0

        val withinThreshold = ConditionalMedianCalculator.evaluate(window, 3.5, 1.0)
        assertEquals(
            withinThreshold.conditionalMedianPower - withinThreshold.medianPower,
            withinThreshold.difference,
            0.0
        )

        val exceedingThreshold = ConditionalMedianCalculator.evaluate(window, 10.0, 1.0)
        assertEquals(
            exceedingThreshold.conditionalMedianPower - exceedingThreshold.medianPower,
            exceedingThreshold.difference,
            0.0
        )
    }

    @Test
    fun `an isolated high outlier used as the reference power is suppressed by the conditional median`() {
        val baselineWindow = doubleArrayOf(0.01, 0.01, 0.01, 0.01, 0.01)
        val spike = 5.0

        val result = ConditionalMedianCalculator.evaluate(
            window = baselineWindow,
            referencePower = spike,
            conditionalMedianThreshold = 0.05
        )

        assertEquals(0.01, result.medianPower, 1e-12)
        assertEquals(0.01, result.conditionalMedianPower, 1e-12)
        assertEquals(0.0, result.difference, 1e-12)
    }

    @Test
    fun `evaluate does not mutate the supplied window`() {
        val window = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)
        val originalOrder = window.copyOf()

        ConditionalMedianCalculator.evaluate(window, referencePower = 10.0, conditionalMedianThreshold = 1.0)

        assertEquals(originalOrder.toList(), window.toList())
    }

    @Test
    fun `evaluate is pure - repeated calls with the same inputs give identical results`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)

        val first = ConditionalMedianCalculator.evaluate(window, 10.0, 1.0)
        val second = ConditionalMedianCalculator.evaluate(window, 10.0, 1.0)

        assertEquals(first, second)
    }

    @Test
    fun `evaluate rejects a negative reference power`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0)

        assertThrows(IllegalArgumentException::class.java) {
            ConditionalMedianCalculator.evaluate(window, referencePower = -1.0, conditionalMedianThreshold = 1.0)
        }
    }

    @Test
    fun `evaluate rejects a non-finite reference power`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0)

        assertThrows(IllegalArgumentException::class.java) {
            ConditionalMedianCalculator.evaluate(
                window,
                referencePower = Double.NaN,
                conditionalMedianThreshold = 1.0
            )
        }
    }

    @Test
    fun `evaluate rejects a negative conditional median threshold`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0)

        assertThrows(IllegalArgumentException::class.java) {
            ConditionalMedianCalculator.evaluate(window, referencePower = 2.0, conditionalMedianThreshold = -1.0)
        }
    }

    @Test
    fun `evaluate rejects a non-finite conditional median threshold`() {
        val window = doubleArrayOf(1.0, 2.0, 3.0)

        assertThrows(IllegalArgumentException::class.java) {
            ConditionalMedianCalculator.evaluate(
                window,
                referencePower = 2.0,
                conditionalMedianThreshold = Double.POSITIVE_INFINITY
            )
        }
    }
}
