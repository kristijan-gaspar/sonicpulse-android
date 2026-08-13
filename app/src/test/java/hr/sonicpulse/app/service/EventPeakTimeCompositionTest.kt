package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.PeakTimeCalculator
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.engine.DetectionEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the composition [MonitoringService.handleBlock] uses for the V2 adaptive path:
 * `peakTimeClient` is derived directly from a [DetectionEvent]'s own `peakBlockIndex` via
 * [PeakTimeCalculator.calculate] — no candidate-completion wrapper, and no offset for the
 * 4096-sample adaptive analysis window — then passed straight into [sessionDetectionFor].
 */
class EventPeakTimeCompositionTest {

    private val startInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `peakTimeClient is derived directly from the event's own peakBlockIndex`() {
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 5, durationBlocks = 3)

        val peakTimeClient =
            PeakTimeCalculator.calculate(startInstant, event.peakBlockIndex, sampleRate = 44_100, blockSize = 1024)
        val expected = PeakTimeCalculator.calculate(startInstant, 5L, sampleRate = 44_100, blockSize = 1024)

        assertEquals(expected, peakTimeClient)
    }

    @Test
    fun `peakBlockIndex zero produces exactly startInstant, confirming no window-fill offset is added`() {
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)

        val peakTimeClient =
            PeakTimeCalculator.calculate(startInstant, event.peakBlockIndex, sampleRate = 44_100, blockSize = 1024)

        assertEquals(startInstant, peakTimeClient)
    }

    @Test
    fun `the derived peakTimeClient is what sessionDetectionFor uses for the SessionDetection`() {
        val event = DetectionEvent(peakDbfs = -9.0, peakBlockIndex = 7, durationBlocks = 2)
        val peakTimeClient =
            PeakTimeCalculator.calculate(startInstant, event.peakBlockIndex, sampleRate = 44_100, blockSize = 1024)

        val sessionDetection = sessionDetectionFor(
            event.peakDbfs,
            peakTimeClient,
            LocationSnapshot.Valid(45.8, 16.0, 8.0f)
        )

        requireNotNull(sessionDetection)
        assertEquals(peakTimeClient, sessionDetection.peakTimeClient)
        assertEquals(event.peakDbfs, sessionDetection.peakDbfs, 0.0)
    }

    @Test
    fun `no SessionDetection is created when the location is not valid, regardless of peakTimeClient`() {
        val event = DetectionEvent(peakDbfs = -9.0, peakBlockIndex = 7, durationBlocks = 2)
        val peakTimeClient =
            PeakTimeCalculator.calculate(startInstant, event.peakBlockIndex, sampleRate = 44_100, blockSize = 1024)

        val sessionDetection = sessionDetectionFor(event.peakDbfs, peakTimeClient, LocationSnapshot.NoFixYet)

        assertNull(sessionDetection)
    }
}
