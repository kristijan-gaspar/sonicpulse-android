package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeMonitoringStateRepositoryTest {

    private fun detection(peakDbfs: Double = -10.0) = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = peakDbfs,
        peakTimeClient = Instant.EPOCH,
        location = LocationSnapshot.NoFixYet
    )

    @Test
    fun `sessionDetections is bounded to the last 100 entries, evicting the oldest`() {
        val repository = FakeMonitoringStateRepository()
        val first = detection(peakDbfs = 0.0)
        repository.localDetectionOccurred(first)
        repeat(100) { repository.localDetectionOccurred(detection(peakDbfs = (it + 1).toDouble())) }

        val detections = repository.state.value.sessionDetections
        assertEquals(100, detections.size)
        assertEquals(false, detections.any { it.localEventId == first.localEventId })
        assertEquals(100.0, detections.last().peakDbfs, 0.0)
    }

    @Test
    fun `a success for an evicted id is a no-op`() {
        val repository = FakeMonitoringStateRepository()
        val evicted = detection()
        repository.localDetectionOccurred(evicted)
        repeat(100) { repository.localDetectionOccurred(detection(peakDbfs = it.toDouble())) }
        check(repository.state.value.sessionDetections.none { it.localEventId == evicted.localEventId })

        repository.submissionSucceeded(evicted.localEventId)

        assertEquals(0, repository.state.value.submissionCounters.submissionSucceeded)
    }

    @Test
    fun `a failure for an evicted id is a no-op`() {
        val repository = FakeMonitoringStateRepository()
        val evicted = detection()
        repository.localDetectionOccurred(evicted)
        repeat(100) { repository.localDetectionOccurred(detection(peakDbfs = it.toDouble())) }
        check(repository.state.value.sessionDetections.none { it.localEventId == evicted.localEventId })

        repository.submissionFailed(evicted.localEventId, SubmissionFailureReason.NetworkError)

        assertEquals(0, repository.state.value.submissionCounters.droppedNetwork)
    }
}
