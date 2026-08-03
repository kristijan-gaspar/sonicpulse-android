package hr.sonicpulse.app.ui.monitoring

import hr.sonicpulse.app.R
import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.datastore.FakePermissionRequestHistory
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun metrics(dbfs: Double = -40.0) = BlockMetrics(
        rms = 0.0,
        dbfs = dbfs,
        baseline = -50.0,
        spike = 10.0,
        crest = null,
        clipRatio = 0.0,
        state = DetectionState.IDLE
    )

    /** Mirrors MonitoringViewModel's own formatter — the device's local zone, not assumed to be UTC. */
    private fun expectedTimestampText(instant: Instant): String =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant)

    private fun detection(
        location: LocationSnapshot = LocationSnapshot.NoFixYet,
        peakTimeClient: Instant = Instant.parse("2026-08-03T14:32:07Z")
    ) = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -10.0,
        peakTimeClient = peakTimeClient,
        location = location
    )

    @Test
    fun `initial ui state is idle with no history and no detection`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        val state = viewModel.uiState.value

        assertEquals(MonitoringPhase.Idle, state.phase)
        assertEquals(LocationDisplayState.Unavailable, state.locationDisplayState)
        assertTrue(state.dbfsHistory.isEmpty())
        assertNull(state.lastDetection)
        assertNull(state.errorMessageRes)
    }

    @Test
    fun `monitoringStarted with no fix yet is AcquiringLocation and Searching`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        repository.monitoringStarted()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MonitoringPhase.AcquiringLocation, state.phase)
        assertEquals(LocationDisplayState.Searching, state.locationDisplayState)
        assertTrue(state.microphoneActive)
        assertTrue(state.backgroundActive)
    }

    @Test
    fun `a valid location fix while monitoring is Listening and Gps`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        repository.monitoringStarted()

        repository.updateLocationStatus(
            snapshot = LocationSnapshot.Valid(45.8, 16.0, 8.0f),
            permissionLevel = LocationPermissionLevel.FINE,
            servicesEnabled = true
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MonitoringPhase.Listening, state.phase)
        assertEquals(LocationDisplayState.Gps, state.locationDisplayState)
    }

    @Test
    fun `coarse permission with an inaccurate fix is PreciseLocationRequired`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        repository.monitoringStarted()

        repository.updateLocationStatus(
            snapshot = LocationSnapshot.Inaccurate(accuracyMeters = 800f),
            permissionLevel = LocationPermissionLevel.COARSE,
            servicesEnabled = true
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MonitoringPhase.PreciseLocationRequired, state.phase)
        assertEquals(LocationDisplayState.PreciseRequired, state.locationDisplayState)
    }

    @Test
    fun `fine permission with an inaccurate fix is still just AcquiringLocation, not PreciseLocationRequired`() =
        runTest(testDispatcher) {
            val repository = FakeMonitoringStateRepository()
            val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
            repository.monitoringStarted()

            repository.updateLocationStatus(
                snapshot = LocationSnapshot.Inaccurate(accuracyMeters = 80f),
                permissionLevel = LocationPermissionLevel.FINE,
                servicesEnabled = true
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(MonitoringPhase.AcquiringLocation, state.phase)
            assertEquals(LocationDisplayState.Searching, state.locationDisplayState)
        }

    @Test
    fun `location services disabled is Unavailable even with an otherwise valid fix`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        repository.monitoringStarted()

        repository.updateLocationStatus(
            snapshot = LocationSnapshot.Valid(45.8, 16.0, 8.0f),
            permissionLevel = LocationPermissionLevel.FINE,
            servicesEnabled = false
        )
        advanceUntilIdle()

        assertEquals(LocationDisplayState.Unavailable, viewModel.uiState.value.locationDisplayState)
    }

    @Test
    fun `location services disabled never leaves phase stuck on Listening from a stale cached fix`() =
        runTest(testDispatcher) {
            val repository = FakeMonitoringStateRepository()
            val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
            repository.monitoringStarted()
            repository.updateLocationStatus(
                snapshot = LocationSnapshot.Valid(45.8, 16.0, 8.0f),
                permissionLevel = LocationPermissionLevel.FINE,
                servicesEnabled = true
            )

            repository.updateLocationStatus(
                snapshot = LocationSnapshot.Valid(45.8, 16.0, 8.0f),
                permissionLevel = LocationPermissionLevel.FINE,
                servicesEnabled = false
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(MonitoringPhase.AcquiringLocation, state.phase)
            assertEquals(LocationDisplayState.Unavailable, state.locationDisplayState)
        }

    @Test
    fun `monitoringStopped returns to Idle even with a residual valid location snapshot`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        repository.monitoringStarted()
        repository.updateLocationStatus(LocationSnapshot.Valid(45.8, 16.0, 8.0f), LocationPermissionLevel.FINE, true)

        repository.monitoringStopped()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MonitoringPhase.Idle, state.phase)
        assertTrue(!state.microphoneActive)
        assertTrue(!state.backgroundActive)
    }

    @Test
    fun `publishMetrics updates currentDbfs and appends to dbfsHistory as Float`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        repository.publishMetrics(metrics(dbfs = -12.5))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(-12.5f, state.currentDbfs, 0.0f)
        assertEquals(listOf(-12.5f), state.dbfsHistory)
    }

    @Test
    fun `a detection with no location fix has null coordinatesText and Sending status`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection(location = LocationSnapshot.NoFixYet)

        repository.localDetectionOccurred(target)
        advanceUntilIdle()

        val last = viewModel.uiState.value.lastDetection
        requireNotNull(last)
        assertEquals(-10.0, last.peakDbfs, 0.0)
        assertEquals(expectedTimestampText(target.peakTimeClient), last.timestampText)
        assertNull(last.coordinatesText)
        assertEquals(SendResult.Sending, last.sendResult)
    }

    @Test
    fun `a detection with a valid location fix formats coordinatesText`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection(location = LocationSnapshot.Valid(45.8, 16.0, 8.0f))

        repository.localDetectionOccurred(target)
        advanceUntilIdle()

        assertEquals("45.80000, 16.00000", viewModel.uiState.value.lastDetection?.coordinatesText)
    }

    @Test
    fun `submissionSucceeded updates the last detection's sendResult to Sent`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)
        advanceUntilIdle()

        assertEquals(SendResult.Sent, viewModel.uiState.value.lastDetection?.sendResult)
    }

    @Test
    fun `submissionFailed with NetworkError maps to FailedNetwork`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)
        advanceUntilIdle()

        assertEquals(SendResult.FailedNetwork, viewModel.uiState.value.lastDetection?.sendResult)
    }

    @Test
    fun `submissionFailed with NoLocation, StaleLocation or InaccurateLocation all map to FailedNoLocation`() =
        runTest(testDispatcher) {
            listOf(
                SubmissionFailureReason.NoLocation,
                SubmissionFailureReason.StaleLocation,
                SubmissionFailureReason.InaccurateLocation
            ).forEach { reason ->
                val repository = FakeMonitoringStateRepository()
                val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
                val target = detection()
                repository.localDetectionOccurred(target)

                repository.submissionFailed(target.localEventId, reason)
                advanceUntilIdle()

                assertEquals("reason $reason", SendResult.FailedNoLocation, viewModel.uiState.value.lastDetection?.sendResult)
            }
        }

    @Test
    fun `submissionFailed with Unauthorized maps to FailedServerConfig`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.Unauthorized)
        advanceUntilIdle()

        assertEquals(SendResult.FailedServerConfig, viewModel.uiState.value.lastDetection?.sendResult)
    }

    @Test
    fun `submissionFailed with other reasons maps to the generic FailedOther, without leaking protocol detail`() =
        runTest(testDispatcher) {
            listOf(
                SubmissionFailureReason.BadRequest,
                SubmissionFailureReason.LocalStorageError,
                SubmissionFailureReason.Cancelled,
                SubmissionFailureReason.RateLimited(retryAfterSeconds = 30L),
                SubmissionFailureReason.ClientError(httpCode = 404),
                SubmissionFailureReason.ServerError(httpCode = 500),
                SubmissionFailureReason.UnexpectedHttpStatus(httpCode = 302)
            ).forEach { reason ->
                val repository = FakeMonitoringStateRepository()
                val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
                val target = detection()
                repository.localDetectionOccurred(target)

                repository.submissionFailed(target.localEventId, reason)
                advanceUntilIdle()

                assertEquals("reason $reason", SendResult.FailedOther, viewModel.uiState.value.lastDetection?.sendResult)
            }
        }

    @Test
    fun `serverConfigurationError is reflected in ui state and stays true after a later success`() =
        runTest(testDispatcher) {
            val repository = FakeMonitoringStateRepository()
            val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
            val first = detection()
            val second = detection()
            repository.localDetectionOccurred(first)
            repository.localDetectionOccurred(second)

            repository.submissionFailed(first.localEventId, SubmissionFailureReason.Unauthorized)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.serverConfigurationError)

            repository.submissionSucceeded(second.localEventId)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.serverConfigurationError)
        }

    @Test
    fun `serverConfigurationError resets to false when a new monitoring session starts`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())
        val target = detection()
        repository.localDetectionOccurred(target)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.Unauthorized)
        advanceUntilIdle()
        check(viewModel.uiState.value.serverConfigurationError)

        repository.monitoringStarted()
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.serverConfigurationError)
    }

    @Test
    fun `a mid-session microphone permission revocation maps to the microphone-denied message`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)
        advanceUntilIdle()

        assertEquals(R.string.error_startup_microphone_denied, viewModel.uiState.value.errorMessageRes)
    }

    @Test
    fun `two identical consecutive failures still change errorEventId so a one-shot Snackbar can re-fire`() =
        runTest(testDispatcher) {
            val repository = FakeMonitoringStateRepository()
            val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

            repository.monitoringFailed(AudioCaptureError.PermissionDenied)
            advanceUntilIdle()
            val firstEventId = viewModel.uiState.value.errorEventId

            repository.monitoringFailed(AudioCaptureError.PermissionDenied)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(R.string.error_startup_microphone_denied, state.errorMessageRes)
            assertTrue(state.errorEventId != firstEventId)
        }

    @Test
    fun `an unexpected capture error maps to the generic capture error message`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        repository.monitoringFailed(AudioCaptureError.UnsupportedConfiguration)
        advanceUntilIdle()

        assertEquals(R.string.error_capture_generic, viewModel.uiState.value.errorMessageRes)
    }

    @Test
    fun `a startup failure maps to its specific message`() = runTest(testDispatcher) {
        val repository = FakeMonitoringStateRepository()
        val viewModel = MonitoringViewModel(repository, FakePermissionRequestHistory())

        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationServicesDisabled)
        advanceUntilIdle()

        assertEquals(R.string.error_startup_location_services_disabled, viewModel.uiState.value.errorMessageRes)
    }
}
