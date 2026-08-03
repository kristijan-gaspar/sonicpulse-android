package hr.sonicpulse.app.ui.monitoring

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun metrics(
        dbfs: Double = -40.0,
        baseline: Double = -50.0,
        state: DetectionState = DetectionState.IDLE
    ) = BlockMetrics(
        rms = 0.0,
        dbfs = dbfs,
        baseline = baseline,
        spike = dbfs - baseline,
        crest = null,
        clipRatio = 0.0,
        state = state
    )

    @Test
    fun `initial ui state mirrors the repository's initial state`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        val state = viewModel.uiState.value

        assertEquals(MonitoringUiState(), state)
    }

    @Test
    fun `ui state reflects monitoring started`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        repository.monitoringStarted()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isMonitoring)
    }

    @Test
    fun `ui state reflects published metrics`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        repository.publishMetrics(metrics(dbfs = -12.0, baseline = -40.0, state = DetectionState.DETECTING))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(-12.0, state.liveDbfs, 0.0)
        assertEquals(-40.0, state.liveBaseline, 0.0)
        assertEquals(DetectionState.DETECTING, state.engineState)
    }

    @Test
    fun `ui state reflects a session detection`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        val detection = SessionDetection(UUID.randomUUID(), -10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)
        repository.localDetectionOccurred(detection)
        advanceUntilIdle()

        assertEquals(listOf(detection), viewModel.uiState.value.sessionDetections)
    }

    @Test
    fun `ui state reflects a capture error`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)
        advanceUntilIdle()

        assertEquals(AudioCaptureError.PermissionDenied, viewModel.uiState.value.captureError)
    }

    @Test
    fun `ui state reflects a startup error`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)

        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationPermissionDenied)
        advanceUntilIdle()

        assertEquals(MonitoringStartupFailure.LocationPermissionDenied, viewModel.uiState.value.startupError)
    }

    @Test
    fun `ui state reflects submission counters and a persistent server configuration error`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository)
        val detection = SessionDetection(UUID.randomUUID(), -10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)
        repository.localDetectionOccurred(detection)

        repository.submissionFailed(detection.localEventId, hr.sonicpulse.app.domain.model.SubmissionFailureReason.UNAUTHORIZED)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.submissionCounters.submissionFailedUnauthorized)
        assertTrue(state.serverConfigurationError)
    }
}
