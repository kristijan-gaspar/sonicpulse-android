package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringSessionCoordinatorTest {

    @Test
    fun `a synchronously reported capture error is forwarded to onCaptureError, never published to the repository`() {
        val repository = FakeMonitoringStateRepository()
        val coordinator = MonitoringSessionCoordinator(repository)
        var forwardedError: AudioCaptureError? = null

        // Simulates AudioRecorder.start() invoking onError synchronously before it returns.
        // The coordinator itself must not decide ownership or touch the repository for it —
        // that is MonitoringService.handleCaptureError's job now.
        coordinator.startSession(
            startCapture = { _, onError -> onError(AudioCaptureError.PermissionDenied) },
            onBlock = { },
            onCaptureError = { forwardedError = it }
        )

        val state = repository.state.value
        assertTrue("monitoringStarted() must still have run before startCapture()", state.isMonitoring)
        assertNull("the coordinator must never publish monitoringFailed() itself", state.captureError)
        assertEquals(AudioCaptureError.PermissionDenied, forwardedError)
    }

    @Test
    fun `a successful capture start leaves monitoring active with no capture error`() {
        val repository = FakeMonitoringStateRepository()
        val coordinator = MonitoringSessionCoordinator(repository)

        coordinator.startSession(
            startCapture = { onBlock, _ -> onBlock(ShortArray(0)) },
            onBlock = { },
            onCaptureError = { }
        )

        val state = repository.state.value
        assertTrue(state.isMonitoring)
        assertNull(state.captureError)
    }

    @Test
    fun `blocks delivered after a successful start are forwarded to onBlock`() {
        val repository = FakeMonitoringStateRepository()
        val coordinator = MonitoringSessionCoordinator(repository)
        val received = mutableListOf<ShortArray>()
        val block = shortArrayOf(1, 2, 3)

        coordinator.startSession(
            startCapture = { onBlock, _ -> onBlock(block) },
            onBlock = { received += it },
            onCaptureError = { }
        )

        assertEquals(listOf(block), received)
    }

    @Test
    fun `endSession publishes monitoringStopped only when the session was still active`() {
        val repository = FakeMonitoringStateRepository()
        val coordinator = MonitoringSessionCoordinator(repository)
        repository.monitoringStarted()

        coordinator.endSession(wasActiveBeforeTeardown = true)

        assertFalse(repository.state.value.isMonitoring)
    }

    @Test
    fun `endSession does not overwrite an already-failed state when the session was not active`() {
        val repository = FakeMonitoringStateRepository()
        val coordinator = MonitoringSessionCoordinator(repository)
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        coordinator.endSession(wasActiveBeforeTeardown = false)

        val state = repository.state.value
        assertFalse(state.isMonitoring)
        assertEquals(AudioCaptureError.PermissionDenied, state.captureError)
    }
}
