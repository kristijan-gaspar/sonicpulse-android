package hr.sonicpulse.app.ui.map

/** Map-provider (style/tile) render state — entirely independent of backend hotspot state (§16).
 * Never combined with [MapUiState]'s `initialError`/`subsequentError` into one flag. */
sealed interface MapRenderState {
    data object Loading : MapRenderState
    data object Loaded : MapRenderState
    data class Failed(val reason: String?) : MapRenderState
}

/**
 * Tracks map-provider render state across MapLibre map instance re-creations (Retry recreates the
 * instance rather than reusing it). Deliberately framework-free (no Compose/MapLibre imports) so
 * the stale-callback protection is plain-JVM-testable — mirrors the generation-token pattern used
 * for backend requests in [MapViewModel], just for map-instance identity instead of network calls.
 *
 * A callback belonging to an older, already-replaced map instance must never mutate the state of
 * whatever instance is current by the time it fires — MapLibre gives no ordering guarantee between
 * a disposed instance's late callback and a new instance's own callbacks.
 */
class MapRenderCoordinator {
    var generation: Int = 0
        private set
    var state: MapRenderState = MapRenderState.Loading
        private set

    /** Call exactly once per (re)created MapLibre map instance — first composition and every
     * Retry. Returns the generation this instance's `onMapLoadFinished`/`onMapLoadFailed`
     * callbacks must be captured with, and resets [state] to [MapRenderState.Loading], clearing
     * any previous instance's failure. */
    fun newInstance(): Int {
        generation++
        state = MapRenderState.Loading
        return generation
    }

    fun onLoadFinished(forGeneration: Int) {
        if (forGeneration == generation) {
            state = MapRenderState.Loaded
        }
    }

    fun onLoadFailed(forGeneration: Int, reason: String?) {
        if (forGeneration == generation) {
            state = MapRenderState.Failed(reason)
        }
    }
}
