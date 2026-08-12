package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveCandidateCompletion
import hr.sonicpulse.engine.adaptive.AdaptiveDetectionState
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Proves the disabled-build-flag path (`BuildConfig.ENABLE_SESSION_LOGGING == false`, wired to
 * this implementation in `di/ObservabilityModule`) collects and allocates nothing. */
class NoOpDetectionSessionLoggerTest {

    private fun readyDiagnostics(trigger: Boolean, completion: AdaptiveCandidateCompletion? = null) = AdaptiveHopDiagnostics(
        hopIndex = 0,
        analysisReady = true,
        dbfs = -10.0,
        power = 0.01,
        crestDb = 12.0,
        clipRatio = 0.0,
        backgroundSampleCount = 216,
        mfa = 0.01,
        stdPower = 0.001,
        cmfa = 0.01,
        tha = 0.005,
        variation = 0.0,
        th = 0.005,
        threshold = 0.015,
        isBootstrapping = false,
        energyExceeded = trigger,
        crestExceeded = trigger,
        clipExceeded = false,
        impulsive = trigger,
        trigger = trigger,
        stateBefore = AdaptiveDetectionState.DETECTING,
        stateAfter = AdaptiveDetectionState.COOLDOWN,
        activeEventThreshold = 0.015,
        candidateCompletion = completion
    )

    @Test
    fun `hasCompletedSession stays false through a full start-block-finish cycle`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(AdaptiveEngineConfig())
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onBlock(readyDiagnostics(trigger = true, completion = AdaptiveCandidateCompletion.Accepted(event)))
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
    }

    @Test
    fun `a rejected completion also leaves hasCompletedSession false and exportJson null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(AdaptiveEngineConfig())
        logger.onBlock(readyDiagnostics(trigger = false))
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `exportJson is always null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession(AdaptiveEngineConfig())
        logger.finishSession()

        assertNull(logger.exportJson())
    }
}
