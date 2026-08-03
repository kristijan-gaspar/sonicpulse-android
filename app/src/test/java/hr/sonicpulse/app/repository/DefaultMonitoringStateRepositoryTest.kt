package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
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
        location = LocationSnapshot.NoFixYet
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

    @Test
    fun `submissionSucceeded marks the matching detection as Sent and increments the counter`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        val other = detection()
        repository.localDetectionOccurred(other)
        repository.localDetectionOccurred(target)

        repository.submissionSucceeded(target.localEventId)

        val state = repository.state.value
        assertEquals(SubmissionStatus.Sent, state.sessionDetections.first { it.localEventId == target.localEventId }.submissionStatus)
        assertEquals(SubmissionStatus.Local, state.sessionDetections.first { it.localEventId == other.localEventId }.submissionStatus)
        assertEquals(1, state.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `submissionFailed marks the matching detection as Failed with the given reason`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.NETWORK_ERROR)

        val state = repository.state.value
        assertEquals(
            SubmissionStatus.Failed(SubmissionFailureReason.NETWORK_ERROR),
            state.sessionDetections.first { it.localEventId == target.localEventId }.submissionStatus
        )
    }

    @Test
    fun `submissionFailed increments the counter matching each failure reason`() {
        val expectedCounter = mapOf(
            SubmissionFailureReason.NO_LOCATION to SubmissionCounters(droppedNoLocation = 1),
            SubmissionFailureReason.STALE_LOCATION to SubmissionCounters(droppedStaleLocation = 1),
            SubmissionFailureReason.INACCURATE_LOCATION to SubmissionCounters(droppedInaccurateLocation = 1),
            SubmissionFailureReason.NETWORK_ERROR to SubmissionCounters(droppedNetwork = 1),
            SubmissionFailureReason.BAD_REQUEST to SubmissionCounters(submissionFailedBadRequest = 1),
            SubmissionFailureReason.UNAUTHORIZED to SubmissionCounters(submissionFailedUnauthorized = 1),
            SubmissionFailureReason.RATE_LIMITED to SubmissionCounters(submissionRateLimited = 1),
            SubmissionFailureReason.CLIENT_ERROR to SubmissionCounters(submissionFailedClient = 1),
            SubmissionFailureReason.SERVER_ERROR to SubmissionCounters(submissionFailedServer = 1)
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
    fun `submissionFailed with UNAUTHORIZED sets a persistent serverConfigurationError`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)

        repository.submissionFailed(target.localEventId, SubmissionFailureReason.UNAUTHORIZED)

        assertTrue(repository.state.value.serverConfigurationError)
    }

    @Test
    fun `serverConfigurationError stays true after a later successful submission`() {
        val repository = DefaultMonitoringStateRepository()
        val first = detection()
        val second = detection()
        repository.localDetectionOccurred(first)
        repository.localDetectionOccurred(second)
        repository.submissionFailed(first.localEventId, SubmissionFailureReason.UNAUTHORIZED)

        repository.submissionSucceeded(second.localEventId)

        assertTrue(repository.state.value.serverConfigurationError)
    }

    @Test
    fun `monitoringStarted resets submission counters and serverConfigurationError from a previous session`() {
        val repository = DefaultMonitoringStateRepository()
        val target = detection()
        repository.localDetectionOccurred(target)
        repository.submissionFailed(target.localEventId, SubmissionFailureReason.UNAUTHORIZED)

        repository.monitoringStarted()

        val state = repository.state.value
        assertEquals(SubmissionCounters(), state.submissionCounters)
        assertFalse(state.serverConfigurationError)
    }
}
