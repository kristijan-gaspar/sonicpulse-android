package hr.sonicpulse.app.repository

/** Rate-limits high-frequency updates (e.g. per-block audio metrics) to at most one per [minIntervalMillis]. */
class MetricsThrottle(
    private val minIntervalMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis
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
}
