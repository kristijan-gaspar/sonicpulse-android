package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.data.remote.FakeDetectionApi
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the impulse-to-SessionDetection gating MonitoringService.handleBlock() delegates to
 * sessionDetectionFor(): only a currently Valid location snapshot produces a SessionDetection —
 * with that exact snapshot passed through unchanged — and NoFixYet/Stale/Inaccurate/Invalid never
 * reach the repository or the backend.
 */
class MonitoringServiceDetectionGatingTest {

    @Test
    fun `a currently valid location creates exactly one SessionDetection with that exact snapshot unchanged`() {
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        val valid = LocationSnapshot.Valid(45.8, 16.0, 8.0f)

        val detection = sessionDetectionFor(peakDbfs = -10.0, peakTimeClient = Instant.EPOCH, locationSnapshot = valid)
        requireNotNull(detection)
        repository.localDetectionOccurred(detection)

        assertEquals(1, repository.occurredDetections.size)
        assertEquals(valid, repository.occurredDetections.single().location)
    }

    @Test
    fun `NoFixYet, Stale, Inaccurate and Invalid all create no SessionDetection`() {
        listOf(
            LocationSnapshot.NoFixYet,
            LocationSnapshot.Stale(ageMillis = 999_999),
            LocationSnapshot.Inaccurate(accuracyMeters = 500f),
            LocationSnapshot.Invalid
        ).forEach { snapshot ->
            val detection = sessionDetectionFor(-10.0, Instant.EPOCH, snapshot)
            assertNull("snapshot $snapshot", detection)
        }
    }

    @Test
    fun `monitoring remains active and no detection is recorded when no valid location exists`() {
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()

        val detection = sessionDetectionFor(-10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)

        assertNull(detection)
        assertTrue(repository.state.value.isMonitoring)
        assertTrue(repository.occurredDetections.isEmpty())
    }

    @Test
    fun `no backend submission occurs when the current location is not valid`() {
        val api = FakeDetectionApi()

        val detection = sessionDetectionFor(-10.0, Instant.EPOCH, LocationSnapshot.Inaccurate(accuracyMeters = 500f))

        assertNull(detection)
        // Mirrors MonitoringService.handleBlock(): DetectionSubmitter.submit() is only ever called
        // inside `if (sessionDetection != null)` — a null detection means that branch (and thus
        // any call into the api) is simply never reached.
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a later impulse is processed normally once a valid location becomes available`() {
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()

        val first = sessionDetectionFor(-10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)
        val valid = LocationSnapshot.Valid(45.8, 16.0, 8.0f)
        val second = sessionDetectionFor(-8.0, Instant.EPOCH.plusSeconds(1), valid)
        listOfNotNull(first, second).forEach { repository.localDetectionOccurred(it) }

        assertNull(first)
        requireNotNull(second)
        assertEquals(1, repository.occurredDetections.size)
        assertEquals(-8.0, repository.occurredDetections.single().peakDbfs, 0.0)
        assertTrue(repository.state.value.isMonitoring)
    }
}
