package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RobustBackgroundVariationTest {

    @Test
    fun `stores the given median power, conditional median power and difference`() {
        val result = RobustBackgroundVariation(
            medianPower = 0.01,
            conditionalMedianPower = 0.03,
            difference = 0.02
        )

        assertEquals(0.01, result.medianPower, 0.0)
        assertEquals(0.03, result.conditionalMedianPower, 0.0)
        assertEquals(0.02, result.difference, 0.0)
    }

    @Test
    fun `difference may be negative`() {
        val result = RobustBackgroundVariation(
            medianPower = 0.03,
            conditionalMedianPower = 0.01,
            difference = -0.02
        )

        assertEquals(-0.02, result.difference, 0.0)
    }

    @Test
    fun `rejects a negative median power`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustBackgroundVariation(medianPower = -0.1, conditionalMedianPower = 0.1, difference = 0.2)
        }
    }

    @Test
    fun `rejects a non-finite median power`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustBackgroundVariation(medianPower = Double.NaN, conditionalMedianPower = 0.1, difference = 0.0)
        }
    }

    @Test
    fun `rejects a negative conditional median power`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustBackgroundVariation(medianPower = 0.1, conditionalMedianPower = -0.1, difference = -0.2)
        }
    }

    @Test
    fun `rejects a non-finite conditional median power`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustBackgroundVariation(
                medianPower = 0.1,
                conditionalMedianPower = Double.POSITIVE_INFINITY,
                difference = 0.0
            )
        }
    }

    @Test
    fun `rejects a non-finite difference`() {
        assertThrows(IllegalArgumentException::class.java) {
            RobustBackgroundVariation(medianPower = 0.1, conditionalMedianPower = 0.1, difference = Double.NaN)
        }
    }
}
