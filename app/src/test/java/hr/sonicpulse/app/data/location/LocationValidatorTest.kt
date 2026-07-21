package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationValidatorTest {

    private val policy = LocationPolicy(maxLocationAgeMillis = 10_000, maxLocationAccuracyMeters = 50.0f)

    private fun fix(
        ageMillis: Long = 0,
        accuracyMeters: Float = 10.0f,
        nowElapsedRealtimeNanos: Long = 1_000_000_000_000L
    ): RawLocationFix = RawLocationFix(
        latitude = 45.0,
        longitude = 15.0,
        accuracyMeters = accuracyMeters,
        elapsedRealtimeNanos = nowElapsedRealtimeNanos - ageMillis * 1_000_000L
    )

    private val now = 1_000_000_000_000L

    @Test
    fun `no permission is reported even when a fresh, accurate fix is available`() {
        val result = LocationValidator.evaluate(
            fix = fix(ageMillis = 0, accuracyMeters = 10.0f, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.NONE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.PermissionDenied, result)
    }

    @Test
    fun `permission granted but no fix yet is reported as NoFixYet`() {
        val result = LocationValidator.evaluate(
            fix = null,
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.NoFixYet, result)
    }

    @Test
    fun `a fix exactly at the max age boundary is still valid`() {
        val result = LocationValidator.evaluate(
            fix = fix(ageMillis = policy.maxLocationAgeMillis, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, 10.0f), result)
    }

    @Test
    fun `a fix one millisecond beyond the max age is stale`() {
        val ageMillis = policy.maxLocationAgeMillis + 1
        val result = LocationValidator.evaluate(
            fix = fix(ageMillis = ageMillis, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Stale(ageMillis), result)
    }

    @Test
    fun `a fix exactly at the max accuracy boundary is still valid`() {
        val result = LocationValidator.evaluate(
            fix = fix(accuracyMeters = policy.maxLocationAccuracyMeters, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, policy.maxLocationAccuracyMeters), result)
    }

    @Test
    fun `a fix just above the max accuracy is inaccurate`() {
        val accuracy = policy.maxLocationAccuracyMeters + 0.01f
        val result = LocationValidator.evaluate(
            fix = fix(accuracyMeters = accuracy, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Inaccurate(accuracy), result)
    }

    @Test
    fun `a fix that is both stale and inaccurate is classified as stale`() {
        val ageMillis = policy.maxLocationAgeMillis + 1
        val accuracy = policy.maxLocationAccuracyMeters + 10.0f
        val result = LocationValidator.evaluate(
            fix = fix(ageMillis = ageMillis, accuracyMeters = accuracy, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.FINE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Stale(ageMillis), result)
    }

    @Test
    fun `a fresh, accurate fix with permission is valid and passes through its coordinates`() {
        val result = LocationValidator.evaluate(
            fix = fix(ageMillis = 100, accuracyMeters = 5.0f, nowElapsedRealtimeNanos = now),
            permissionLevel = LocationPermissionLevel.COARSE,
            policy = policy,
            nowElapsedRealtimeNanos = now
        )

        assertEquals(LocationSnapshot.Valid(45.0, 15.0, 5.0f), result)
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
