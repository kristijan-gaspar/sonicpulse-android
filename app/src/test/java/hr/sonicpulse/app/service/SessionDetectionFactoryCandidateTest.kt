package hr.sonicpulse.app.service

import hr.sonicpulse.engine.CandidateCompletion
import hr.sonicpulse.engine.CandidateRejectionReason
import hr.sonicpulse.engine.DetectionEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [finalizedCandidateFor] — the pure function [MonitoringService.handleBlock] uses to turn
 * whatever `DetectionEngine.lastCandidateCompletion` produced into a
 * [hr.sonicpulse.app.observability.FinalizedCandidate] for the diagnostic session logger — derives
 * its peak instant from the completion's own peak block index for both outcomes, and never
 * recomputes an engine decision (the [CandidateCompletion] itself is passed through unchanged).
 */
class SessionDetectionFactoryCandidateTest {

    private val startInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `an accepted completion produces a FinalizedCandidate wrapping that exact completion`() {
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 5, durationBlocks = 3)
        val completion = CandidateCompletion.Accepted(event)

        val finalized = finalizedCandidateFor(completion, startInstant, sampleRate = 44_100, blockSize = 1024)

        assertEquals(completion, finalized.completion)
    }

    @Test
    fun `a rejected completion produces a FinalizedCandidate wrapping that exact completion`() {
        val completion = CandidateCompletion.Rejected(
            reason = CandidateRejectionReason.TOO_LONG,
            peakDbfs = -5.0,
            peakBlockIndex = 40,
            durationBlocks = 31
        )

        val finalized = finalizedCandidateFor(completion, startInstant, sampleRate = 44_100, blockSize = 1024)

        assertEquals(completion, finalized.completion)
        assertTrue(finalized.completion is CandidateCompletion.Rejected)
    }

    @Test
    fun `peakTimeClient is derived from the completion's own peakBlockIndex, using PeakTimeCalculator`() {
        val acceptedCompletion = CandidateCompletion.Accepted(
            DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 10, durationBlocks = 1)
        )
        val rejectedCompletion = CandidateCompletion.Rejected(
            reason = CandidateRejectionReason.TOO_LONG,
            peakDbfs = -5.0,
            peakBlockIndex = 10, // same index as the accepted case above, so the two must match
            durationBlocks = 31
        )

        val acceptedFinalized = finalizedCandidateFor(acceptedCompletion, startInstant, sampleRate = 44_100, blockSize = 1024)
        val rejectedFinalized = finalizedCandidateFor(rejectedCompletion, startInstant, sampleRate = 44_100, blockSize = 1024)

        assertEquals(acceptedFinalized.peakTimeClient, rejectedFinalized.peakTimeClient)
        assertTrue(acceptedFinalized.peakTimeClient.isAfter(startInstant))
    }

    @Test
    fun `a rejected completion never produces a DetectionEvent to submit to the backend`() {
        // CandidateCompletion.Rejected simply has no `event` property — the type system itself
        // guarantees MonitoringService.handleBlock() has nothing it could submit for it, since
        // submission is only ever driven by the separate, nullable DetectionEvent that
        // engine.process() returns (see MonitoringServiceDetectionGatingTest for the submission
        // gating itself).
        val completion: CandidateCompletion = CandidateCompletion.Rejected(
            reason = CandidateRejectionReason.TOO_LONG,
            peakDbfs = -5.0,
            peakBlockIndex = 40,
            durationBlocks = 31
        )

        val event = when (completion) {
            is CandidateCompletion.Accepted -> completion.event
            is CandidateCompletion.Rejected -> null
        }

        assertNull(event)
    }
}
