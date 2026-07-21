package hr.sonicpulse.app.data.location

/**
 * Pure bookkeeping for a single location-start attempt's identity and pending-callback delivery
 * — extracted so DefaultLocationProvider's async start/stop/stale-callback handling can be unit
 * tested without a real FusedLocationProviderClient/LocationCallback/Task. The token is an
 * opaque identity (in practice, DefaultLocationProvider's own LocationCallback instance) that
 * lets a late Play Services callback recognize whether it still belongs to the current attempt.
 * Not thread-confined itself — DefaultLocationProvider is responsible for synchronizing access.
 */
class LocationStartTracker {

    sealed interface BeginResult {
        data class Accepted(val token: Any) : BeginResult
        data object Rejected : BeginResult
    }

    sealed interface CompleteResult {
        data class Deliver(val onResult: (LocationStartResult) -> Unit) : CompleteResult
        data object Stale : CompleteResult
    }

    private var currentToken: Any? = null
    private var pendingOnResult: ((LocationStartResult) -> Unit)? = null

    /** True while [token] is the current attempt — pending or already confirmed Started — used
     * to gate whether an incoming location fix may update state. False once stopped, resolved
     * to a non-Started result, or superseded by a later attempt. */
    fun isCurrent(token: Any): Boolean = currentToken === token

    /** Rejected if an attempt is already pending or confirmed started — the caller must not
     * register a second, duplicate location request. */
    fun begin(token: Any, onResult: (LocationStartResult) -> Unit): BeginResult {
        if (currentToken != null) {
            return BeginResult.Rejected
        }
        currentToken = token
        pendingOnResult = onResult
        return BeginResult.Accepted(token)
    }

    /**
     * Resolves the attempt identified by [token]. [Started] keeps [token] current (so
     * [isCurrent] keeps gating location updates for it) but clears the pending callback, since
     * it has now been delivered. Any other result fully resets the tracker, so a later [begin]
     * is accepted (a restart after a startup failure). A [token] that no longer matches the
     * current one — already invalidated by [stop], superseded by a later attempt, or already
     * resolved once — returns [CompleteResult.Stale] without touching state.
     */
    fun complete(token: Any, result: LocationStartResult): CompleteResult {
        if (currentToken !== token) {
            return CompleteResult.Stale
        }
        val onResult = pendingOnResult ?: return CompleteResult.Stale
        pendingOnResult = null
        if (result !is LocationStartResult.Started) {
            currentToken = null
        }
        return CompleteResult.Deliver(onResult)
    }

    /** Idempotent: returns the pending onResult (to invoke with Cancelled), if an attempt was
     * genuinely still pending, and always fully resets the tracker so a later [begin] is
     * accepted. Returns null if nothing was pending (already resolved, or never started). */
    fun stop(): ((LocationStartResult) -> Unit)? {
        val onResult = pendingOnResult
        currentToken = null
        pendingOnResult = null
        return onResult
    }
}
