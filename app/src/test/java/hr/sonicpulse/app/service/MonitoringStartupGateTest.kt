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
        startForeground: () -> ForegroundStartOutcome = { ForegroundStartOutcome.Started }
    ) = MonitoringStartupGate(hasRecordAudioPermission, locationPermissionLevel, startForeground)

    @Test
    fun `missing RECORD_AUDIO denies startup without checking location or attempting to promote`() {
        var locationChecked = false
        var startForegroundCalled = false
        val result = gate(
            hasRecordAudioPermission = { false },
            locationPermissionLevel = { locationChecked = true; LocationPermissionLevel.FINE },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied),
            result
        )
        assertFalse(locationChecked)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `missing location permission denies startup without attempting to promote`() {
        var startForegroundCalled = false
        val result = gate(
            hasRecordAudioPermission = { true },
            locationPermissionLevel = { LocationPermissionLevel.NONE },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied),
            result
        )
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `an IllegalStateException during foreground promotion is a foreground start failure`() {
        val cause = IllegalStateException("not allowed to start a foreground service in this app state")
        val result = gate(
            startForeground = { ForegroundStartOutcome.Failed(cause) }
        ).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)
        val failure = (result as MonitoringStartupResult.Failed).failure
        assertTrue(failure is MonitoringStartupFailure.ForegroundStartFailed)
        assertSame(cause, (failure as MonitoringStartupFailure.ForegroundStartFailed).cause)
    }

    @Test
    fun `a SecurityException during promotion is attributed to microphone when RECORD_AUDIO is no longer granted`() {
        var recordAudioCheckCount = 0
        val result = gate(
            hasRecordAudioPermission = {
                recordAudioCheckCount++
                recordAudioCheckCount == 1 // granted pre-promotion, revoked by the time we attribute the failure
            },
            startForeground = { ForegroundStartOutcome.PermissionDenied }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied),
            result
        )
    }

    @Test
    fun `a SecurityException during promotion is attributed to location when RECORD_AUDIO is still granted`() {
        val result = gate(
            hasRecordAudioPermission = { true },
            startForeground = { ForegroundStartOutcome.PermissionDenied }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied),
            result
        )
    }

    @Test
    fun `both permissions granted and successful promotion allows startup to proceed`() {
        val result = gate().attemptStartup()

        assertEquals(MonitoringStartupResult.Proceed, result)
    }

    @Test
    fun `RECORD_AUDIO revoked between the pre-check and the post-promotion re-check denies startup`() {
        var checkCount = 0
        val result = gate(
            hasRecordAudioPermission = {
                checkCount++
                checkCount == 1 // granted pre-promotion, revoked on the post-promotion re-check
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.MicrophonePermissionDenied),
            result
        )
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

        assertEquals(
            MonitoringStartupResult.Failed(MonitoringStartupFailure.LocationPermissionDenied),
            result
        )
    }
}
