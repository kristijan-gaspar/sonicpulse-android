package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves submitDetectionSafely() is the boundary that stops an unexpected exception from
 * DetectionSubmitter.submit() (see its KDoc: only IOException is caught there, everything else is
 * deliberately left to propagate) from ever escaping the root coroutine that runs it, while still
 * leaving the detection in a terminal, consistent state instead of stuck Pending forever.
 */
class SubmissionSafetyNetTest {

    private fun detection() = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -10.0,
        peakTimeClient = Instant.EPOCH,
        location = LocationSnapshot.Valid(45.8, 16.0, 8.0f)
    )

    @Test
    fun `an unexpected exception is contained and reported as UnexpectedError, not rethrown`() = runTest {
        val repository = FakeMonitoringStateRepository()
        val detection = detection()
        repository.localDetectionOccurred(detection)
        var logged: Throwable? = null
        val cause = IllegalStateException("malformed response body")

        submitDetectionSafely(
            monitoringStateRepository = repository,
            detection = detection,
            logUnexpected = { logged = it }
        ) {
            throw cause
        }

        assertSame(cause, logged)
        val status = repository.state.value.sessionDetections.single { it.localEventId == detection.localEventId }.submissionStatus
        assertTrue(status is SubmissionStatus.Failed)
        assertEquals(SubmissionFailureReason.UnexpectedError, (status as SubmissionStatus.Failed).reason)
    }

    @Test
    fun `CancellationException propagates untouched and is never reported as a failure`() = runTest {
        val repository = FakeMonitoringStateRepository()
        val detection = detection()
        repository.localDetectionOccurred(detection)
        var logged: Throwable? = null

        var thrown: CancellationException? = null
        try {
            submitDetectionSafely(
                monitoringStateRepository = repository,
                detection = detection,
                logUnexpected = { logged = it }
            ) {
                throw CancellationException("cancelled")
            }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue(thrown != null)
        assertNull(logged)
        val status = repository.state.value.sessionDetections.single { it.localEventId == detection.localEventId }.submissionStatus
        assertEquals(SubmissionStatus.Pending, status)
    }

    @Test
    fun `a successful attempt reports nothing and leaves the detection untouched here`() = runTest {
        val repository = FakeMonitoringStateRepository()
        val detection = detection()
        repository.localDetectionOccurred(detection)
        var logged: Throwable? = null

        submitDetectionSafely(
            monitoringStateRepository = repository,
            detection = detection,
            logUnexpected = { logged = it }
        ) {
            // Successful attempt: real DetectionSubmitter.submit() would call
            // monitoringStateRepository.submissionSucceeded() itself — this fake attempt simply
            // returns, proving submitDetectionSafely() does not report a failure when none occurs.
        }

        assertNull(logged)
        val status = repository.state.value.sessionDetections.single { it.localEventId == detection.localEventId }.submissionStatus
        assertEquals(SubmissionStatus.Pending, status)
    }
}
