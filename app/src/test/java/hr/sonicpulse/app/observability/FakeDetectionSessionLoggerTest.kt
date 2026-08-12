package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies [FakeDetectionSessionLogger] tracks [DetectionSessionLogger.hasCompletedSession] the
 * way the real implementation does, so tests built on it (e.g. [MonitoringViewModel] UI-state
 * tests) can trust it as a stand-in for session availability. */
class FakeDetectionSessionLoggerTest {

    @Test
    fun `startSession sets hasCompletedSession to false`() {
        val logger = FakeDetectionSessionLogger(initialHasCompletedSession = true)

        logger.startSession(AdaptiveEngineConfig())

        assertEquals(false, logger.hasCompletedSession.value)
    }

    @Test
    fun `finishSession sets hasCompletedSession to true when a session was started`() {
        val logger = FakeDetectionSessionLogger()

        logger.startSession(AdaptiveEngineConfig())
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
    }

    @Test
    fun `finishSession without a prior startSession does not fabricate a completed session`() {
        val logger = FakeDetectionSessionLogger()

        logger.finishSession()

        assertEquals(false, logger.hasCompletedSession.value)
        assertEquals(1, logger.finishSessionCallCount)
    }

    @Test
    fun `calling finishSession twice stays safe and keeps the call counter accurate`() {
        val logger = FakeDetectionSessionLogger()
        logger.startSession(AdaptiveEngineConfig())

        logger.finishSession()
        logger.finishSession()

        assertEquals(true, logger.hasCompletedSession.value)
        assertEquals(2, logger.finishSessionCallCount)
    }
}
