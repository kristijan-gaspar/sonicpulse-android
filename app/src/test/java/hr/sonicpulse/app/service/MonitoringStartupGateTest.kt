package hr.sonicpulse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MonitoringStartupGateTest {

    @Test
    fun `missing RECORD_AUDIO denies startup without attempting to promote to foreground`() {
        var startForegroundCalled = false
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { false },
            startForeground = { startForegroundCalled = true; true }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
        assertFalse(startForegroundCalled)
    }

    @Test
    fun `a SecurityException during foreground promotion denies startup`() {
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { true },
            startForeground = { false } // simulates the caught SecurityException
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
    }

    @Test
    fun `permission granted and successful promotion allows startup to proceed`() {
        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = { true },
            startForeground = { true }
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
            startForeground = { true }
        )

        val result = gate.attemptStartup()

        assertEquals(MonitoringStartupResult.PermissionDenied, result)
    }
}
