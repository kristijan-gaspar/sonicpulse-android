package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
import hr.sonicpulse.app.service.LocationRefreshFailure
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DefaultMonitoringStateRepositoryTest {

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

    private fun detection(peakDbfs: Double = -10.0) = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = peakDbfs,
        peakTimeClient = Instant.EPOCH,
        location = LocationSnapshot.Valid(45.8, 16.0, 8.0f)
    )

    @Test
    fun `initial state is idle and not monitoring with no session detections`() {
        val repository = DefaultMonitoringStateRepository()

        val state = repository.state.value

        assertFalse(state.isMonitoring)
        assertEquals(DetectionState.IDLE, state.engineState)
        assertTrue(state.sessionDetections.isEmpty())
        assertNull(state.captureError)
        assertNull(state.startupError)
    }

    @Test
    fun `monitoringStartupFailed sets isMonitoring false and stores the startup error`() {
        val repository = DefaultMonitoringStateRepository()

        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationPermissionDenied)

        val state = repository.state.value
        assertFalse(state.isMonitoring)
        assertEquals(MonitoringStartupFailure.LocationPermissionDenied, state.startupError)
    }

    @Test
    fun `monitoringStarted clears a previous startup error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStartupFailed(MonitoringStartupFailure.MicrophonePermissionDenied)

        repository.monitoringStarted()

        assertNull(repository.state.value.startupError)
    }

    @Test
    fun `monitoringStopped does not set a startup error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStarted()

        repository.monitoringStopped()

        assertNull(repository.state.value.startupError)
    }

    @Test
    fun `monitoringFailed sets isMonitoring false and stores the capture error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStarted()

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        val state = repository.state.value
        assertFalse(state.isMonitoring)
        assertEquals(AudioCaptureError.PermissionDenied, state.captureError)
    }

    @Test
    fun `two identical consecutive capture failures still bump errorEventId each time`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)
        val firstId = repository.state.value.errorEventId

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        assertEquals(firstId + 1, repository.state.value.errorEventId)
    }

    @Test
    fun `two identical consecutive startup failures still bump errorEventId each time`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationPermissionDenied)
        val firstId = repository.state.value.errorEventId

        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationPermissionDenied)

        assertEquals(firstId + 1, repository.state.value.errorEventId)
    }

    @Test
    fun `monitoringStarted clears a previous capture error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        repository.monitoringStarted()

        assertNull(repository.state.value.captureError)
    }

    @Test
    fun `monitoringStopped does not set a capture error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStarted()

        repository.monitoringStopped()

        assertNull(repository.state.value.captureError)
    }

    @Test
    fun `monitoringProcessingFailed sets isMonitoring false and the processing error flag`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStarted()

        repository.monitoringProcessingFailed()

        val state = repository.state.value
        assertFalse(state.isMonitoring)
        assertTrue(state.processingError)
    }

    @Test
    fun `monitoringProcessingFailed clears any previous capture or startup error so none are set together`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        repository.monitoringProcessingFailed()

        val state = repository.state.value
        assertNull(state.captureError)
        assertNull(state.startupError)
        assertTrue(state.processingError)
    }

    @Test
    fun `monitoringFailed clears a previous processing error so both are never set together`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringProcessingFailed()

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        val state = repository.state.value
        assertFalse(state.processingError)
        assertEquals(AudioCaptureError.PermissionDenied, state.captureError)
    }

    @Test
    fun `monitoringStarted clears a previous processing error`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringProcessingFailed()

        repository.monitoringStarted()

        assertFalse(repository.state.value.processingError)
    }

    @Test
    fun `monitoringProcessingFailed bumps errorEventId`() {
        val repository = DefaultMonitoringStateRepository()
        val firstId = repository.state.value.errorEventId

        repository.monitoringProcessingFailed()

        assertEquals(firstId + 1, repository.state.value.errorEventId)
    }

    @Test
    fun `monitoringStartupFailed clears a previous capture error so both are never set together`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationServicesDisabled)

        val state = repository.state.value
        assertNull(state.captureError)
        assertEquals(MonitoringStartupFailure.LocationServicesDisabled, state.startupError)
    }

    @Test
    fun `monitoringFailed clears a previous startup error so both are never set together`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStartupFailed(MonitoringStartupFailure.LocationServicesDisabled)

        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        val state = repository.state.value
        assertNull(state.startupError)
        assertEquals(AudioCaptureError.PermissionDenied, state.captureError)
    }

    @Test
    fun `monitoringStarted clears both capture and startup errors`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringFailed(AudioCaptureError.PermissionDenied)

        repository.monitoringStarted()

        val state = repository.state.value
        assertNull(state.captureError)
        assertNull(state.startupError)
    }

    @Test
    fun `monitoringStarted sets isMonitoring true`() {
        val repository = DefaultMonitoringStateRepository()

        repository.monitoringStarted()

        assertTrue(repository.state.value.isMonitoring)
    }

    @Test
    fun `monitoringStopped sets isMonitoring false`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStarted()

        repository.monitoringStopped()

        assertFalse(repository.state.value.isMonitoring)
    }

    @Test
    fun `monitoringStarted resets session detections from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        repository.localDetectionOccurred(detection())
        check(repository.state.value.sessionDetections.isNotEmpty())

        repository.monitoringStarted()

        assertTrue(repository.state.value.sessionDetections.isEmpty())
    }

    @Test
    fun `publishMetrics updates liveDbfs, liveBaseline and engineState`() {
        val repository = DefaultMonitoringStateRepository()

        repository.publishMetrics(metrics(dbfs = -25.0, baseline = -60.0, state = DetectionState.DETECTING))

        val state = repository.state.value
        assertEquals(-25.0, state.liveDbfs, 0.0)
        assertEquals(-60.0, state.liveBaseline, 0.0)
        assertEquals(DetectionState.DETECTING, state.engineState)
    }

    @Test
    fun `a second publishMetrics call immediately after the first is throttled and dropped`() {
        val repository = DefaultMonitoringStateRepository()

        repository.publishMetrics(metrics(dbfs = -25.0))
        repository.publishMetrics(metrics(dbfs = -5.0))

        assertEquals(-25.0, repository.state.value.liveDbfs, 0.0)
    }

    @Test
    fun `monitoringStarted resets the metrics throttle so a new session's first metric is never throttled by the previous session's`() {
        val repository = DefaultMonitoringStateRepository()
        repository.publishMetrics(metrics(dbfs = -25.0)) // previous session's last (throttle-consuming) emission

        repository.monitoringStarted()
        repository.publishMetrics(metrics(dbfs = -5.0)) // new session's first metric

        // Without the reset, this would still be throttled by the emission above (both land in the
        // same instant under a JVM unit test's stubbed SystemClock.elapsedRealtime()).
        assertEquals(-5.0, repository.state.value.liveDbfs, 0.0)
    }

    @Test
    fun `publishMetrics appends the dbfs value to dbfsHistory`() {
        val repository = DefaultMonitoringStateRepository()

        repository.publishMetrics(metrics(dbfs = -25.0))

        assertEquals(listOf(-25.0), repository.state.value.dbfsHistory)
    }

    @Test
    fun `a throttled publishMetrics call does not append to dbfsHistory`() {
        val repository = DefaultMonitoringStateRepository()

        repository.publishMetrics(metrics(dbfs = -25.0))
        repository.publishMetrics(metrics(dbfs = -5.0))

        assertEquals(listOf(-25.0), repository.state.value.dbfsHistory)
    }

    @Test
    fun `updateLocationStatus updates the snapshot, permission level and services-enabled flag`() {
        val repository = DefaultMonitoringStateRepository()

        repository.updateLocationStatus(
            snapshot = LocationSnapshot.Valid(latitude = 45.8, longitude = 16.0, accuracyMeters = 8.0f),
            permissionLevel = LocationPermissionLevel.FINE,
            servicesEnabled = true
        )

        val state = repository.state.value
        assertEquals(LocationSnapshot.Valid(45.8, 16.0, 8.0f), state.currentLocationSnapshot)
        assertEquals(LocationPermissionLevel.FINE, state.locationPermissionLevel)
        assertTrue(state.locationServicesEnabled)
    }

    @Test
    fun `monitoringStarted resets location status from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        repository.updateLocationStatus(
            snapshot = LocationSnapshot.Valid(45.8, 16.0, 8.0f),
            permissionLevel = LocationPermissionLevel.COARSE,
            servicesEnabled = false
        )

        repository.monitoringStarted()

        val state = repository.state.value
        assertEquals(LocationSnapshot.NoFixYet, state.currentLocationSnapshot)
        assertEquals(LocationPermissionLevel.NONE, state.locationPermissionLevel)
        assertTrue(state.locationServicesEnabled)
    }

    @Test
    fun `localDetectionOccurred appends to sessionDetections`() {
        val repository = DefaultMonitoringStateRepository()
        val detection = detection(peakDbfs = -12.0)

        repository.localDetectionOccurred(detection)

        assertEquals(listOf(detection), repository.state.value.sessionDetections)
    }

    @Test
    fun `sessionDetections is bounded to the last 100 entries`() {
        val repository = DefaultMonitoringStateRepository()

        repeat(101) { repository.localDetectionOccurred(detection(peakDbfs = it.toDouble())) }

        val detections = repository.state.value.sessionDetections
        assertEquals(100, detections.size)
        assertEquals(1.0, detections.first().peakDbfs, 0.0)
        assertEquals(100.0, detections.last().peakDbfs, 0.0)
    }

    private fun statusOf(repository: DefaultMonitoringStateRepository, id: UUID) =
        repository.state.value.sessionDetections.first { it.localEventId == id }.submissionStatus

    @Test
    fun `newly created SessionDetection defaults to Pending`() {
        assertEquals(SubmissionStatus.Pending, detection().submissionStatus)
    }

    @Test
    fun `submissionSucceeded marks the matching detection as Sent and increments the counter`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        val other = detection()
        repository.localDetectionOccurred(other)
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)

        assertEquals(SubmissionStatus.Sent, statusOf(repository, target.localEventId))
        assertEquals(SubmissionStatus.Pending, statusOf(repository, other.localEventId))
        assertEquals(1, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `submissionFailed marks the matching detection as Failed with the given reason`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.NetworkError), statusOf(repository, target.localEventId))
    }

    @Test
    fun `submissionFailed increments the counter matching each failure reason`() {
        val expectedCounter = mapOf(
            SubmissionFailureReason.NoLocation to SubmissionCounters(droppedNoLocation = 1),
            SubmissionFailureReason.StaleLocation to SubmissionCounters(droppedStaleLocation = 1),
            SubmissionFailureReason.InaccurateLocation to SubmissionCounters(droppedInaccurateLocation = 1),
            SubmissionFailureReason.LocalStorageError to SubmissionCounters(droppedLocalStorage = 1),
            SubmissionFailureReason.NetworkError to SubmissionCounters(droppedNetwork = 1),
            SubmissionFailureReason.Cancelled to SubmissionCounters(cancelled = 1),
            SubmissionFailureReason.BadRequest to SubmissionCounters(submissionFailedBadRequest = 1),
            SubmissionFailureReason.Unauthorized to SubmissionCounters(submissionFailedUnauthorized = 1),
            SubmissionFailureReason.RateLimited(30L) to SubmissionCounters(submissionRateLimited = 1),
            SubmissionFailureReason.ClientError(404) to SubmissionCounters(submissionFailedClient = 1),
            SubmissionFailureReason.ServerError(500) to SubmissionCounters(submissionFailedServer = 1),
            SubmissionFailureReason.UnexpectedHttpStatus(302) to SubmissionCounters(submissionFailedUnexpected = 1)
        )

        expectedCounter.forEach { (reason, expected) ->
            val repository = DefaultMonitoringStateRepository()
            val target = detection()
            repository.localDetectionOccurred(target)

            repository.submissionFailed(target.localEventId, reason)

            assertEquals("reason $reason", expected, repository.state.value.submissionCounters)
        }
    }

    @Test
    fun `submissionFailed with Unauthorized sets a persistent serverConfigurationError for the rest of the session`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.Unauthorized)

        assertTrue(repository.state.value.serverConfigurationError)
    }

    @Test
    fun `serverConfigurationError stays true after a later successful submission`() {
        val repository = DefaultMonitoringStateRepository()
        val first = detection()
        val second = detection()
        repository.localDetectionOccurred(first)
        repository.localDetectionOccurred(second)
        repository.submissionFailed(first.localEventId, SubmissionFailureReason.Unauthorized)

        repository.submissionSucceeded(second.localEventId)

        assertTrue(repository.state.value.serverConfigurationError)
    }

    @Test
    fun `monitoringStarted resets submission counters and serverConfigurationError from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.Unauthorized)

        repository.monitoringStarted()

        val state = repository.state.value
        assertEquals(SubmissionCounters(), state.submissionCounters)
        assertFalse(state.serverConfigurationError)
    }

    @Test
    fun `submissionSucceeded for an unknown localEventId is a no-op`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(UUID.randomUUID())

        assertEquals(SubmissionStatus.Pending, statusOf(repository, target.localEventId))
        assertEquals(0, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `submissionFailed for an unknown localEventId is a no-op`() {
        val repository = DefaultMonitoringStateRepository()

        repository.submissionFailed(UUID.randomUUID(), SubmissionFailureReason.NetworkError)

        assertEquals(SubmissionCounters(), repository.state.value.submissionCounters)
    }

    @Test
    fun `duplicate submissionSucceeded does not increment the counter twice`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)
        repository.submissionSucceeded(target.localEventId)

        assertEquals(1, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `duplicate submissionFailed does not increment the counter twice`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        assertEquals(1, repository.state.value.submissionCounters.droppedNetwork)
    }

    @Test
    fun `success followed by failure remains Sent`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        assertEquals(SubmissionStatus.Sent, statusOf(repository, target.localEventId))
        assertEquals(0, repository.state.value.submissionCounters.droppedNetwork)
    }

    @Test
    fun `failure followed by success remains the original Failed`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)
        repository.submissionSucceeded(target.localEventId)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.NetworkError), statusOf(repository, target.localEventId))
        assertEquals(0, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `a result for a detection evicted by the 100-item retention limit is a no-op`() {
        val repository = DefaultMonitoringStateRepository()
        val evicted = detection()
        repository.localDetectionOccurred(evicted)
        repeat(100) { repository.localDetectionOccurred(detection(peakDbfs = it.toDouble())) }
        check(repository.state.value.sessionDetections.none { it.localEventId == evicted.localEventId })

        repository.submissionSucceeded(evicted.localEventId)

        assertEquals(0, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `an old-session result arriving after monitoringStarted resets state is a no-op`() {
        val repository = DefaultMonitoringStateRepository()
        val staleId = detection().localEventId

        repository.monitoringStarted()
        repository.submissionSucceeded(staleId)

        assertTrue(repository.state.value.sessionDetections.isEmpty())
        assertEquals(0, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `cancelPendingSubmissions transitions a single Pending detection to Failed(Cancelled)`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.cancelPendingSubmissions()

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.Cancelled), statusOf(repository, target.localEventId))
        assertEquals(1, repository.state.value.submissionCounters.cancelled)
    }

    @Test
    fun `cancelPendingSubmissions transitions multiple Pending detections`() {
        val repository = DefaultMonitoringStateRepository()
        val first = detection()
        val second = detection()
        repository.localDetectionOccurred(first)
        repository.localDetectionOccurred(second)

        repository.cancelPendingSubmissions()

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.Cancelled), statusOf(repository, first.localEventId))
        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.Cancelled), statusOf(repository, second.localEventId))
        assertEquals(2, repository.state.value.submissionCounters.cancelled)
    }

    @Test
    fun `cancelPendingSubmissions leaves an already-Sent detection unchanged`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)
        repository.submissionSucceeded(target.localEventId)

        repository.cancelPendingSubmissions()

        assertEquals(SubmissionStatus.Sent, statusOf(repository, target.localEventId))
        assertEquals(0, repository.state.value.submissionCounters.cancelled)
    }

    @Test
    fun `cancelPendingSubmissions leaves an already-Failed detection unchanged`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        repository.cancelPendingSubmissions()

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.NetworkError), statusOf(repository, target.localEventId))
        assertEquals(0, repository.state.value.submissionCounters.cancelled)
    }

    @Test
    fun `repeated cancelPendingSubmissions does not double-count`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.cancelPendingSubmissions()
        repository.cancelPendingSubmissions()

        assertEquals(
            SubmissionStatus.Failed(SubmissionFailureReason.Cancelled),
            repository.state.value.sessionDetections.single().submissionStatus
        )
        assertEquals(1, repository.state.value.submissionCounters.cancelled)
    }

    @Test
    fun `cancelPendingSubmissions called twice, mirroring explicit Stop then onDestroy, does not touch an already-terminal detection`() {
        val repository = DefaultMonitoringStateRepository()
        val sent = detection()
        val failed = detection()
        repository.localDetectionOccurred(sent)
        repository.localDetectionOccurred(failed)
        repository.submissionSucceeded(sent.localEventId)
        repository.submissionFailed(failed.localEventId, SubmissionFailureReason.NetworkError)

        repository.cancelPendingSubmissions()
        repository.cancelPendingSubmissions()

        val state = repository.state.value
        assertEquals(SubmissionStatus.Sent, state.sessionDetections.first { it.localEventId == sent.localEventId }.submissionStatus)
        assertEquals(
            SubmissionStatus.Failed(SubmissionFailureReason.NetworkError),
            state.sessionDetections.first { it.localEventId == failed.localEventId }.submissionStatus
        )
        assertEquals(0, state.submissionCounters.cancelled)
    }

    @Test
    fun `monitoringStartupFailed with a microphone or location permission denial increments permissionFailures`() {
        listOf(MonitoringStartupFailure.MicrophonePermissionDenied, MonitoringStartupFailure.LocationPermissionDenied)
            .forEach { failure ->
                val repository = DefaultMonitoringStateRepository()

                repository.monitoringStartupFailed(failure)

                assertEquals("failure $failure", 1, repository.state.value.submissionCounters.permissionFailures)
            }
    }

    @Test
    fun `monitoringStartupFailed with a non-permission failure does not increment permissionFailures`() {
        listOf(
            MonitoringStartupFailure.LocationServicesDisabled,
            MonitoringStartupFailure.LocationStartFailed(RuntimeException()),
            MonitoringStartupFailure.ForegroundStartFailed(RuntimeException())
        ).forEach { failure ->
            val repository = DefaultMonitoringStateRepository()

            repository.monitoringStartupFailed(failure)

            assertEquals("failure $failure", 0, repository.state.value.submissionCounters.permissionFailures)
        }
    }

    @Test
    fun `locationRefreshFailed with PermissionDenied increments permissionFailures`() {
        val repository = DefaultMonitoringStateRepository()

        repository.locationRefreshFailed(LocationRefreshFailure.PermissionDenied)

        assertEquals(1, repository.state.value.submissionCounters.permissionFailures)
    }

    @Test
    fun `locationRefreshFailed with a non-permission failure does not increment permissionFailures`() {
        listOf(LocationRefreshFailure.LocationServicesDisabled, LocationRefreshFailure.Failed).forEach { failure ->
            val repository = DefaultMonitoringStateRepository()

            repository.locationRefreshFailed(failure)

            assertEquals("failure $failure", 0, repository.state.value.submissionCounters.permissionFailures)
        }
    }

    @Test
    fun `monitoringStarted resets permissionFailures from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        repository.monitoringStartupFailed(MonitoringStartupFailure.MicrophonePermissionDenied)

        repository.monitoringStarted()

        assertEquals(0, repository.state.value.submissionCounters.permissionFailures)
    }

    @Test
    fun `localDetectionOccurred increments localDetectionCount exactly once`() {
        val repository = DefaultMonitoringStateRepository()

        repository.localDetectionOccurred(detection())

        assertEquals(1, repository.state.value.localDetectionCount)
    }

    @Test
    fun `submission status changes do not affect localDetectionCount`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        assertEquals(1, repository.state.value.localDetectionCount)
    }

    @Test
    fun `monitoringStarted resets localDetectionCount from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        repository.localDetectionOccurred(detection())
        check(repository.state.value.localDetectionCount > 0)

        repository.monitoringStarted()

        assertEquals(0, repository.state.value.localDetectionCount)
    }

    @Test
    fun `more than 100 local detections still produce the correct uncapped count while the retained list stays capped at 100`() {
        val repository = DefaultMonitoringStateRepository()

        repeat(105) { repository.localDetectionOccurred(detection(peakDbfs = it.toDouble())) }

        assertEquals(105, repository.state.value.localDetectionCount)
        assertEquals(100, repository.state.value.sessionDetections.size)
    }
}
