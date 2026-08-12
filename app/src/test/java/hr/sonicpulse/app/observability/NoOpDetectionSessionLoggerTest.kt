package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.CandidateCompletion
import hr.sonicpulse.engine.CandidateRejectionReason
import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.DetectionState
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Proves the disabled-build-flag path (`BuildConfig.ENABLE_SESSION_LOGGING == false`, wired to
 * this implementation in `di/ObservabilityModule`) collects and allocates nothing. */
class NoOpDetectionSessionLoggerTest {

    @Test
    fun `hasCompletedSession stays false through a full start-block-finish cycle`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(EngineConfig())
        val metrics = BlockMetrics(
            rms = 0.0, dbfs = -10.0, baseline = -60.0, spike = 50.0, crest = 10.0,
            clipRatio = 0.0, state = DetectionState.DETECTING, blockIndex = 0
        )
        val completion = CandidateCompletion.Accepted(DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1))
        logger.onBlock(metrics, FinalizedCandidate(completion, Instant.EPOCH))
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
    }

    @Test
    fun `a rejected completion also leaves hasCompletedSession false and exportJson null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(EngineConfig())
        val metrics = BlockMetrics(
            rms = 0.0, dbfs = -10.0, baseline = -60.0, spike = 20.0, crest = 10.0,
            clipRatio = 0.0, state = DetectionState.COOLDOWN, blockIndex = 30
        )
        val completion = CandidateCompletion.Rejected(
            reason = CandidateRejectionReason.TOO_LONG, peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 31
        )
        logger.onBlock(metrics, FinalizedCandidate(completion, Instant.EPOCH))
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `exportJson is always null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(EngineConfig())
        logger.finishSession()

        assertNull(logger.exportJson())
    }
}
