package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStartupGateTest {

    private fun gate(
        hasRecordAudioPermission: () -> Boolean = { true },
        locationPermissionLevel: () -> LocationPermissionLevel = { LocationPermissionLevel.FINE },
        areLocationServicesEnabled: () -> Boolean = { true },
        startForeground: () -> ForegroundStartOutcome = { ForegroundStartOutcome.Started }
    ) = MonitoringStartupGate(hasRecordAudioPermission, locationPermissionLevel, areLocationServicesEnabled, startForeground)

    @Test
    fun `missing RECORD_AUDIO denies startup without checking anything else`() {
        var locationChecked = false
        var servicesChecked = false
        var startForegroundCalled = false
        val result = gate(
            hasRecordAudioPermission = { false },
            locationPermissionLevel = { locationChecked = true; LocationPermissionLevel.FINE },
            areLocationServicesEnabled = { servicesChecked = true; true },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied), result)
        assertFalse(locationChecked)
        assertFalse(servicesChecked)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `missing location permission denies startup without checking location services or promoting`() {
        var servicesChecked = false
        var startForegroundCalled = false
        val result = gate(
            locationPermissionLevel = { LocationPermissionLevel.NONE },
            areLocationServicesEnabled = { servicesChecked = true; true },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied), result)
        assertFalse(servicesChecked)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `disabled location services deny startup without attempting to promote`() {
        var startForegroundCalled = false
        val result = gate(
            areLocationServicesEnabled = { false },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationServicesDisabled), result)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `an IllegalStateException-derived foreground failure is reported as ForegroundStartFailed`() {
        val cause = IllegalStateException("not allowed to start a foreground service in this app state")
        val result = gate(startForeground = { ForegroundStartOutcome.Failed(cause) }).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)
        val failure = (result as MonitoringStartupResult.Failed).failure
        assertTrue(failure is MonitoringStartupFailure.ForegroundStartFailed)
        assertSame(cause, (failure as MonitoringStartupFailure.ForegroundStartFailed).cause)
    }

    @Test
    fun `a SecurityException during promotion is attributed to microphone when RECORD_AUDIO is no longer granted`() {
        var recordAudioCheckCount = 0
        val cause = SecurityException("record audio")
        val result = gate(
            hasRecordAudioPermission = {
                recordAudioCheckCount++
                recordAudioCheckCount == 1 // granted pre-promotion, revoked by attribution time
            },
            startForeground = { ForegroundStartOutcome.PermissionDenied(cause) }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied), result)
    }

    @Test
    fun `a SecurityException during promotion is attributed to location when its permission is no longer granted`() {
        var locationCheckCount = 0
        val cause = SecurityException("location")
        val result = gate(
            locationPermissionLevel = {
                locationCheckCount++
                if (locationCheckCount == 1) LocationPermissionLevel.FINE else LocationPermissionLevel.NONE
            },
            startForeground = { ForegroundStartOutcome.PermissionDenied(cause) }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied), result)
    }

    @Test
    fun `a SecurityException during promotion with both permissions still granted is an unattributed foreground failure preserving the original cause`() {
        val cause = SecurityException("unexplained")
        val result = gate(startForeground = { ForegroundStartOutcome.PermissionDenied(cause) }).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)
        val failure = (result as MonitoringStartupResult.Failed).failure
        assertTrue(failure is MonitoringStartupFailure.ForegroundStartFailed)
        assertSame(cause, (failure as MonitoringStartupFailure.ForegroundStartFailed).cause)
    }

    @Test
    fun `all checks passing and successful promotion allows startup to proceed`() {
        val result = gate().attemptStartup()

        assertEquals(MonitoringStartupResult.Proceed, result)
    }

    @Test
    fun `RECORD_AUDIO revoked between the pre-check and the post-promotion re-check denies startup`() {
        var checkCount = 0
        val result = gate(
            hasRecordAudioPermission = {
                checkCount++
                checkCount == 1
            }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied), result)
    }

    @Test
    fun `location permission revoked between the pre-check and the post-promotion re-check denies startup`() {
        var checkCount = 0
        val result = gate(
            locationPermissionLevel = {
                checkCount++
                if (checkCount == 1) LocationPermissionLevel.FINE else LocationPermissionLevel.NONE
            }
        ).attemptStartup()

        assertEquals(MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied), result)
    }
}
