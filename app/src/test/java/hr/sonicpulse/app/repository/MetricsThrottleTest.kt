package hr.sonicpulse.app.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsThrottleTest {

    @Test
    fun `the first call is always allowed`() {
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { 0L })

        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `a call before the interval has elapsed is rejected`() {
        var now = 0L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })

        throttle.shouldEmit()
        now = 50L

        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun `a call exactly at the interval boundary is allowed`() {
        var now = 0L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })

        throttle.shouldEmit()
        now = 100L

        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `a call after the interval has elapsed is allowed and resets the window`() {
        var now = 0L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })

        throttle.shouldEmit()
        now = 150L
        assertTrue(throttle.shouldEmit())

        now = 200L
        assertFalse(throttle.shouldEmit())
    }
}
