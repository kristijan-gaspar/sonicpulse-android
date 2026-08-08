package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves currentSubmittableLocationSnapshot() ignores a cached Valid snapshot the instant
 * permission is revoked or Location Services is disabled — the exact runtime-eligibility gap
 * DefaultLocationProvider.currentSnapshot alone cannot close, since its last fix is never cleared
 * by either event.
 */
class LocationSubmissionEligibilityTest {

    private val cachedValid = LocationSnapshot.Valid(45.8, 16.0, 8.0f)

    @Test
    fun `a cached Valid snapshot is ignored once location permission is revoked`() {
        val result = currentSubmittableLocationSnapshot(
            permissionLevel = LocationPermissionLevel.NONE,
            servicesEnabled = true,
            snapshot = cachedValid
        )

        assertNull(result)
    }

    @Test
    fun `a cached Valid snapshot is ignored once Location Services is disabled`() {
        val result = currentSubmittableLocationSnapshot(
            permissionLevel = LocationPermissionLevel.FINE,
            servicesEnabled = false,
            snapshot = cachedValid
        )

        assertNull(result)
    }

    @Test
    fun `a cached Valid snapshot is ignored when both permission and services are unavailable`() {
        val result = currentSubmittableLocationSnapshot(
            permissionLevel = LocationPermissionLevel.NONE,
            servicesEnabled = false,
            snapshot = cachedValid
        )

        assertNull(result)
    }

    @Test
    fun `a Valid snapshot passes through unchanged when permission and services are both available`() {
        val result = currentSubmittableLocationSnapshot(
            permissionLevel = LocationPermissionLevel.COARSE,
            servicesEnabled = true,
            snapshot = cachedValid
        )

        assertEquals(cachedValid, result)
    }

    @Test
    fun `a non-Valid snapshot still passes through when eligible — the Valid check is sessionDetectionFor's job, not this gate's`() {
        val result = currentSubmittableLocationSnapshot(
            permissionLevel = LocationPermissionLevel.FINE,
            servicesEnabled = true,
            snapshot = LocationSnapshot.NoFixYet
        )

        assertEquals(LocationSnapshot.NoFixYet, result)
    }
}
