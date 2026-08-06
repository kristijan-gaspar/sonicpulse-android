package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationStartResult
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wires MonitoringLifecycleCoordinator + MonitoringSessionRunner + MonitoringSessionCoordinator
 * together the same way MonitoringService does, and mirrors handleCaptureError's exact ordering
 * inline, to prove the specific race the redesign closes: a capture error produced on the capture
 * thread, superseded by an explicit Stop before the service ever gets to process it, must be a
 * complete no-op — never a repository mutation that could overwrite the normal stopped state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringServiceCaptureErrorRaceTest {

    private class Session(val generation: Long)

    @Test
    fun `a capture error superseded by an explicit Stop before it is handled leaves the repository normally stopped`() =
        runTest {
            val repository = FakeMonitoringStateRepository()
            val sessionCoordinator = MonitoringSessionCoordinator(repository)
            val lifecycleCoordinator = MonitoringLifecycleCoordinator()
            lateinit var runner: MonitoringSessionRunner<Session>
            runner = MonitoringSessionRunner(
                lifecycleCoordinator = lifecycleCoordinator,
                scope = this,
                sessions = object : MonitoringSessionRunner.Sessions<Session> {
                    override fun create(generation: Long) = Session(generation)
                    override suspend fun tearDown(session: Session?, wasActive: Boolean) {
                        sessionCoordinator.endSession(wasActive)
                    }
                    override fun onIdle() = Unit
                    override fun onRestart(generation: Long, session: Session) = Unit
                    override fun onTeardownFailure(throwable: Throwable) = Unit
                }
            )

            val session = runner.start()
            requireNotNull(session)
            val startEffect = lifecycleCoordinator.onLocationStartResult(session.generation, LocationStartResult.Started)
            check(startEffect == MonitoringLifecycleEffect.StartAudioCapture)

            var deferredCaptureError: (() -> Unit)? = null

            // Mirrors startAudioCapture(): monitoringStarted() runs, capture "starts", and the
            // failure AudioRecorder would report is captured here rather than invoked immediately
            // — simulating it arriving asynchronously, on the capture thread.
            sessionCoordinator.startSession(
                startCapture = { _, onError ->
                    deferredCaptureError = { onError(AudioCaptureError.PermissionDenied) }
                },
                onBlock = { },
                onCaptureError = { error ->
                    // Mirrors MonitoringService.handleCaptureError()'s required ordering exactly:
                    // the identity check and the monitoringFailed() publish happen together.
                    if (runner.currentSession === session) {
                        repository.monitoringFailed(error)
                        runner.stop()
                    }
                }
            )

            // Explicit Stop lands first — invalidates/removes the session before the capture
            // error (queued above) is ever handled.
            runner.stop().join()
            assertNull(runner.currentSession)

            // Only now is the stale capture error finally handled.
            deferredCaptureError?.invoke()

            val state = repository.state.value
            assertFalse("a stale capture error must never resurrect isMonitoring=true", state.isMonitoring)
            assertNull("a stale capture error must never set captureError after a normal Stop", state.captureError)
        }
}
