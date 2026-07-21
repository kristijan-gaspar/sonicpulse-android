package hr.sonicpulse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStartupGateTest {

    @Test
    fun `missing RECORD_AUDIO denies startup without attempting to promote to foreground`() {
        var startForegroundCalled = false
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { false },
            startForeground = { startForegroundCalled = true; ForegroundStartOutcome.Started }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `a SecurityException during foreground promotion is reported as permission denied`() {
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { true },
            startForeground = { ForegroundStartOutcome.PermissionDenied }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
    }

    @Test
    fun `an IllegalStateException during foreground promotion is reported as a foreground start failure, not permission denied`() {
        val cause = IllegalStateException("not allowed to start a foreground service in this app state")
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { true },
            startForeground = { ForegroundStartOutcome.Failed(cause) }
        )

        val result = gate.attemptStartup()

        assertTrue(result is MonitoringStartupResult.ForegroundStartFailed)
        assertSame(cause, (result as MonitoringStartupResult.ForegroundStartFailed).cause)
    }

    @Test
    fun `permission granted and successful promotion allows startup to proceed`() {
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { true },
            startForeground = { ForegroundStartOutcome.Started }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.Proceed, result)
    }

    @Test
    fun `permission revoked between the pre-check and promotion denies startup`() {
        var checkCount = 0
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = {
                checkCount++
                checkCount == 1 // granted on the first (pre-promotion) check, revoked on the second
            },
            startForeground = { ForegroundStartOutcome.Started }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
    }
}
