package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationValidatorTest {

    private val policy = LocationPolicy(maxLocationAgeMillis = 10_000, maxLocationAccuracyMeters = 50.0f)
    private val now = 1_000_000_000_000L

    private fun fix(
        elapsedRealtimeNanos: Long,
        accuracyMeters: Float = 10.0f
    ): RawLocationFix = RawLocationFix(
        latitude = 45.0,
        longitude = 15.0,
        accuracyMeters = accuracyMeters,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    @Test
    fun `no fix yet is reported when there is no fix at all`() {
        val result = LocationValidator.evaluate(fix = null, policy = policy, nowElapsedRealtimeNanos = now)

        assertEquals(LocationSnapshot.NoFixYet, result)
    }

    @Test
    fun `a fix timestamped exactly now is valid`() {
        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos = now), policy, now)

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, 10.0f), result)
    }

    @Test
    fun `a fix timestamped one nanosecond in the future is invalid`() {
        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos = now + 1), policy, now)

        assertEquals(LocationSnapshot.Invalid, result)
    }

    @Test
    fun `a fix timestamped far in the future is invalid`() {
        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos = now + 1_000_000_000_000L), policy, now)

        assertEquals(LocationSnapshot.Invalid, result)
    }

    @Test
    fun `a fix exactly at the max age boundary is still valid`() {
        val maxAgeNanos = policy.maxLocationAgeMillis * 1_000_000L
        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos = now - maxAgeNanos), policy, now)

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, 10.0f), result)
    }

    @Test
    fun `a fix one nanosecond beyond the max age boundary is stale`() {
        val maxAgeNanos = policy.maxLocationAgeMillis * 1_000_000L
        val elapsedRealtimeNanos = now - maxAgeNanos - 1
        val expectedAgeMillis = (now - elapsedRealtimeNanos) / 1_000_000L

        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos), policy, now)

        assertEquals(LocationSnapshot.Stale(expectedAgeMillis), result)
    }

    @Test
    fun `a fix exactly at the max accuracy boundary is still valid`() {
        val result = LocationValidator.evaluate(
            fix(elapsedRealtimeNanos = now, accuracyMeters = policy.maxLocationAccuracyMeters),
            policy,
            now
        )

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, policy.maxLocationAccuracyMeters), result)
    }

    @Test
    fun `a fix just above the max accuracy is inaccurate`() {
        val accuracy = policy.maxLocationAccuracyMeters + 0.01f
        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos = now, accuracyMeters = accuracy), policy, now)

        assertEquals(LocationSnapshot.Inaccurate(accuracy), result)
    }

    @Test
    fun `a fix that is both stale and inaccurate is classified as stale`() {
        val maxAgeNanos = policy.maxLocationAgeMillis * 1_000_000L
        val elapsedRealtimeNanos = now - maxAgeNanos - 1
        val expectedAgeMillis = (now - elapsedRealtimeNanos) / 1_000_000L
        val accuracy = policy.maxLocationAccuracyMeters + 10.0f

        val result = LocationValidator.evaluate(fix(elapsedRealtimeNanos, accuracy), policy, now)

        assertEquals(LocationSnapshot.Stale(expectedAgeMillis), result)
    }

    @Test
    fun `RawLocationFix rejects negative accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawLocationFix(latitude = 45.0, longitude = 15.0, accuracyMeters = -1.0f, elapsedRealtimeNanos = 0)
        }
    }

    @Test
    fun `RawLocationFix rejects out-of-range latitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawLocationFix(latitude = 91.0, longitude = 15.0, accuracyMeters = 1.0f, elapsedRealtimeNanos = 0)
        }
    }

    @Test
    fun `RawLocationFix rejects out-of-range longitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawLocationFix(latitude = 45.0, longitude = 181.0, accuracyMeters = 1.0f, elapsedRealtimeNanos = 0)
        }
    }

    @Test
    fun `RawLocationFix rejects negative elapsedRealtimeNanos`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawLocationFix(latitude = 45.0, longitude = 15.0, accuracyMeters = 1.0f, elapsedRealtimeNanos = -1)
        }
    }
}
