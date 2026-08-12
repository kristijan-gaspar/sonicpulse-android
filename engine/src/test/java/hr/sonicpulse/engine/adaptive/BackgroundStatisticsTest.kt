package hr.sonicpulse.engine.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundStatisticsTest {

    @Test
    fun `stores the given median, standard deviation and sample count`() {
        val stats = BackgroundStatistics(medianPower = 0.01, stdPower = 0.002, sampleCount = 216)

        assertEquals(0.01, stats.medianPower, 0.0)
        assertEquals(0.002, stats.stdPower, 0.0)
        assertEquals(216, stats.sampleCount)
    }

    @Test
    fun `rejects a negative median`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = -0.1, stdPower = 0.0, sampleCount = 1)
        }
    }

    @Test
    fun `rejects a non-finite median`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = Double.NaN, stdPower = 0.0, sampleCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = Double.POSITIVE_INFINITY, stdPower = 0.0, sampleCount = 1)
        }
    }

    @Test
    fun `rejects a negative standard deviation`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = 0.1, stdPower = -0.01, sampleCount = 1)
        }
    }

    @Test
    fun `rejects a non-finite standard deviation`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = 0.1, stdPower = Double.NaN, sampleCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = 0.1, stdPower = Double.POSITIVE_INFINITY, sampleCount = 1)
        }
    }

    @Test
    fun `rejects a negative sample count`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundStatistics(medianPower = 0.1, stdPower = 0.01, sampleCount = -1)
        }
    }
}
