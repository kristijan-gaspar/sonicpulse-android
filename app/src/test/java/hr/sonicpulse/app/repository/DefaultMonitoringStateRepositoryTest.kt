package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        peakTimeClient = Instant.EPOCH
    )

    @Test
    fun `initial state is idle and not monitoring with no session detections`() {
        val repository = DefaultMonitoringStateRepository()

        val state = repository.state.value

        assertFalse(state.isMonitoring)
        assertEquals(DetectionState.IDLE, state.engineState)
        assertTrue(state.sessionDetections.isEmpty())
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
}
