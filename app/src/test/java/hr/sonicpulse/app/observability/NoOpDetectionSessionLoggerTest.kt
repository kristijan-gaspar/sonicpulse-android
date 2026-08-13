package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveDetectionState
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Proves the disabled-build-flag path (`BuildConfig.ENABLE_SESSION_LOGGING == false`, wired to
 * this implementation in `di/ObservabilityModule`) collects and allocates nothing. */
class NoOpDetectionSessionLoggerTest {

    private fun diagnostics(trigger: Boolean = false) = AdaptiveHopDiagnostics(
        hopIndex = 0,
        analysisReady = true,
        dbfs = -10.0,
        power = 0.01,
        crestDb = 12.0,
        clipRatio = 0.0,
        mfa = 0.01,
        variation = 0.0,
        th = 0.005,
        threshold = 0.015,
        isBootstrapping = false,
        energyExceeded = trigger,
        relativePowerExceeded = trigger,
        impulsive = trigger,
        trigger = trigger,
        stateBefore = AdaptiveDetectionState.IDLE,
        stateAfter = if (trigger) AdaptiveDetectionState.DETECTING else AdaptiveDetectionState.IDLE
    )

    @Test
    fun `hasCompletedSession stays false through a full start-hop-finish cycle`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession()
        val event = DetectionEvent(peakDbfs = -10.0, peakBlockIndex = 0, durationBlocks = 1)
        logger.onHop(diagnostics(trigger = true), event)
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
    }

    @Test
    fun `a hop with no closing event also leaves hasCompletedSession false and exportJson null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession()
        logger.onHop(diagnostics(trigger = false), null)
        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertNull(logger.exportJson())
    }

    @Test
    fun `exportJson is always null`() {
        val logger = NoOpDetectionSessionLogger()

        logger.startSession()
        logger.finishSession()

        assertNull(logger.exportJson())
    }
}
