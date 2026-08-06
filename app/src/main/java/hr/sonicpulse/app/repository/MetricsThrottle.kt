package hr.sonicpulse.app.repository

import android.os.SystemClock

/**
 * Rate-limits high-frequency updates (e.g. per-block audio metrics) to at most one per
 * [minIntervalMillis]. [nowMillis] defaults to [SystemClock.elapsedRealtime] — monotonic time
 * since boot, immune to a wall-clock change (user/NTP adjustment, timezone change) — never
 * [System.currentTimeMillis], which could freeze or jump the throttle if the wall clock moves.
 * Always injected as a parameter in tests for deterministic timing.
 */
class MetricsThrottle(
    private val minIntervalMillis: Long,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private var lastEmittedAt: Long? = null

    fun shouldEmit(): Boolean {
        val now = nowMillis()
        val last = lastEmittedAt
        if (last != null && now - last < minIntervalMillis) {
            return false
        }
        lastEmittedAt = now
        return true
    }

    /** Clears any remembered emission time — the next [shouldEmit] call is always allowed,
     * regardless of how recently one occurred before this. Called at the start of a new
     * monitoring session so it never inherits a previous session's throttle timestamp. */
    fun reset() {
        lastEmittedAt = null
    }
}
