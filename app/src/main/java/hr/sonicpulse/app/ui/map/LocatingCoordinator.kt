package hr.sonicpulse.app.ui.map

/** One current-location-button press. `generation` is the identity a location result or timeout
 * must match to be allowed to complete this specific attempt — never a raw coroutine `Job` in UI
 * state (§5.3). */
data class LocatingAttempt(val generation: Long, val active: Boolean)

/**
 * Pure, framework-free coordinator for the current-location button's "locating…" spinner —
 * mirrors [MapRenderCoordinator]'s generation-token pattern, just for locating attempts instead of
 * map instances. A location result and a timeout race each other for the same attempt; whichever
 * arrives first completes it, and the other is then stale and a no-op. Starting a new attempt
 * (a fresh button press) immediately invalidates whatever attempt preceded it — its own late
 * result or timeout can never re-activate or otherwise affect the new one.
 */
class LocatingCoordinator {
    var attempt: LocatingAttempt = LocatingAttempt(generation = 0L, active = false)
        private set

    /** Starts a new attempt and returns its generation — capture this in both the location-result
     * observer and the timeout job so each can identify itself when completing later. */
    fun start(): Long {
        val newGeneration = attempt.generation + 1
        attempt = LocatingAttempt(generation = newGeneration, active = true)
        return newGeneration
    }

    /** Completes the attempt identified by [forGeneration], whether the caller is a real location
     * result or a timeout — the reason doesn't matter, only identity does. Returns `true` if this
     * call actually changed anything (i.e. wasn't stale), so a caller can distinguish "I genuinely
     * just finished the current attempt" from "I was too late and did nothing". */
    fun complete(forGeneration: Long): Boolean {
        if (forGeneration != attempt.generation || !attempt.active) return false
        attempt = attempt.copy(active = false)
        return true
    }

    /** Unconditionally stops whatever attempt is currently active — used when an external
     * condition (location services becoming unavailable) invalidates the attempt regardless of
     * generation identity. Deliberately does *not* bump the generation: the current attempt's own
     * generation stays current, so a still-in-flight timeout for it later calls [complete] with a
     * matching generation but finds [LocatingAttempt.active] already `false` and correctly no-ops
     * instead of surfacing a stale "unavailable" message. */
    fun cancel() {
        if (attempt.active) {
            attempt = attempt.copy(active = false)
        }
    }
}
