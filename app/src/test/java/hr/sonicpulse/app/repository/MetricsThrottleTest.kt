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

    @Test
    fun `reset allows the next call regardless of how recently the previous one occurred`() {
        var now = 0L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })
        throttle.shouldEmit()
        now = 10L // well inside the interval — would otherwise still be throttled

        throttle.reset()

        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `a restarted session's first metric is not throttled by the previous session's last emission`() {
        var now = 0L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })
        throttle.shouldEmit() // previous session's last emission
        now = 1_000_000L // far later — a new session starting long afterward

        throttle.reset() // what DefaultMonitoringStateRepository.monitoringStarted() does
        now = 1_000_005L // the new session's very first block, milliseconds after reset

        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `a simulated backward clock jump fails closed rather than emitting incorrectly`() {
        // Documents the exact vulnerability a non-monotonic wall clock would have had: this
        // throttle never actually sees a clock go backward in production (SystemClock.elapsedRealtime()
        // is monotonic since boot, immune to a wall-clock/NTP adjustment), but if it somehow did,
        // shouldEmit() must degrade safely — reject, never crash or emit spuriously.
        var now = 1_000L
        val throttle = MetricsThrottle(minIntervalMillis = 100, nowMillis = { now })
        throttle.shouldEmit()
        now = 500L // clock moved backward

        assertFalse(throttle.shouldEmit())
    }
}
