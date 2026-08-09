package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the default request interval and its *configured* relationship to the freshness policy
 * value — both are static config numbers, and comparing them is exactly that: a config check, not
 * proof of runtime delivery behavior. `LocationRequest`'s interval is a desired cadence for Fused
 * Location, not a delivery guarantee, so a fix can still arrive slower (or faster) than requested
 * regardless of what these two constants say. This test only pins the *intent* (a nominal 2 s
 * margin) so a future edit to either constant that erodes that intended margin is caught here;
 * whether fixes actually stay fresh at runtime must be validated on a physical device, never
 * inferred from this comparison alone.
 */
class LocationPolicyTest {

    @Test
    fun `default request interval is 8 seconds`() {
        assertEquals(8_000L, LocationPolicy().updateIntervalMillis)
    }

    @Test
    fun `default request interval leaves a nominal 2 second margin below the freshness budget, in configuration`() {
        val policy = LocationPolicy()

        assertTrue(
            "updateIntervalMillis (${policy.updateIntervalMillis}) should leave a nominal margin " +
                "below maxLocationAgeMillis (${policy.maxLocationAgeMillis}) in configuration — " +
                "this does not guarantee actual delivery lands within that margin at runtime",
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
