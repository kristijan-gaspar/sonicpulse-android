package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the default request cadence and its relationship to the freshness policy — the exact
 * invariant PR4's battery optimization depends on: a fix requested this often must still be well
 * inside maxLocationAgeMillis by the time LocationValidator next evaluates it, not sitting at the
 * edge where ordinary FLP delivery jitter could tip a good fix into Stale.
 */
class LocationPolicyTest {

    @Test
    fun `default request interval is 8 seconds`() {
        assertEquals(8_000L, LocationPolicy().updateIntervalMillis)
    }

    @Test
    fun `default request interval stays well inside the freshness budget, not at its edge`() {
        val policy = LocationPolicy()

        assertTrue(
            "updateIntervalMillis (${policy.updateIntervalMillis}) should leave a real margin " +
                "below maxLocationAgeMillis (${policy.maxLocationAgeMillis}), not sit at its edge",
            policy.updateIntervalMillis <= policy.maxLocationAgeMillis - 1_000L
        )
    }

    @Test
    fun `rejects a non-positive max location age`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationPolicy(maxLocationAgeMillis = 0)
        }
    }

    @Test
    fun `rejects a non-positive max location accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationPolicy(maxLocationAccuracyMeters = 0f)
        }
    }

    @Test
    fun `rejects a non-finite max location accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationPolicy(maxLocationAccuracyMeters = Float.NaN)
        }
    }

    @Test
    fun `rejects a non-positive update interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationPolicy(updateIntervalMillis = 0)
        }
    }
}
