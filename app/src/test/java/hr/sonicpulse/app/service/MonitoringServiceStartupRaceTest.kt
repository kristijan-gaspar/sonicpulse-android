package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationStartResult
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wires MonitoringLifecycleCoordinator + MonitoringSessionCoordinator together the same way
 * MonitoringService.startAudioCapture() does, to prove the exact condition its location-polling
 * startup guard depends on: `lifecycleCoordinator.state == ACTIVE` right after
 * `sessionCoordinator.startSession()` returns is false when the capture failed synchronously
 * (mirroring Dispatchers.Main.immediate running the capture-error teardown inline, before
 * startSession() returns), and true when capture actually started.
 */
class MonitoringServiceStartupRaceTest {

    @Test
    fun `a synchronous capture failure during startup leaves the coordinator IDLE, not ACTIVE`() {
        val repository = FakeMonitoringStateRepository()
        val lifecycleCoordinator = MonitoringLifecycleCoordinator()
        val sessionCoordinator = MonitoringSessionCoordinator(repository)
        val generation = (lifecycleCoordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        val startEffect = lifecycleCoordinator.onLocationStartResult(generation, LocationStartResult.Started)
        check(startEffect == MonitoringLifecycleEffect.StartAudioCapture)
        check(lifecycleCoordinator.state == MonitoringLifecycleState.ACTIVE)

        // Mirrors MonitoringService: AudioRecorder.start() fails synchronously, and
        // onCaptureError (mirroring handleCaptureError) tears the lifecycle down immediately.
        sessionCoordinator.startSession(
            startCapture = { _, onError -> onError(AudioCaptureError.PermissionDenied) },
            onBlock = { },
            onCaptureError = { lifecycleCoordinator.onStopOrDestroy() }
        )

        assertEquals(MonitoringLifecycleState.IDLE, lifecycleCoordinator.state)
    }

    @Test
    fun `a successful capture start leaves the coordinator ACTIVE, safe to start location polling`() {
        val repository = FakeMonitoringStateRepository()
        val lifecycleCoordinator = MonitoringLifecycleCoordinator()
        val sessionCoordinator = MonitoringSessionCoordinator(repository)
        val generation = (lifecycleCoordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        lifecycleCoordinator.onLocationStartResult(generation, LocationStartResult.Started)

        sessionCoordinator.startSession(
            startCapture = { onBlock, _ -> onBlock(ShortArray(0)) },
            onBlock = { },
            onCaptureError = { lifecycleCoordinator.onStopOrDestroy() }
        )

        assertEquals(MonitoringLifecycleState.ACTIVE, lifecycleCoordinator.state)
    }

    @Test
    fun `an explicit Stop racing in right after a successful start also leaves the coordinator IDLE`() {
        val repository = FakeMonitoringStateRepository()
        val lifecycleCoordinator = MonitoringLifecycleCoordinator()
        val sessionCoordinator = MonitoringSessionCoordinator(repository)
        val generation = (lifecycleCoordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        lifecycleCoordinator.onLocationStartResult(generation, LocationStartResult.Started)
        sessionCoordinator.startSession(
            startCapture = { onBlock, _ -> onBlock(ShortArray(0)) },
            onBlock = { },
            onCaptureError = { }
        )

        // Simulates ACTION_STOP delivered by the system right after startAudioCapture() finished
        // wiring capture, before this service instance would have checked the guard.
        lifecycleCoordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleState.IDLE, lifecycleCoordinator.state)
    }
}
