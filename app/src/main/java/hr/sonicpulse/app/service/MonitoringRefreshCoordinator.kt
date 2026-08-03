package hr.sonicpulse.app.service

sealed interface MonitoringRefreshEffect {
    data class Begin(val generation: Long) : MonitoringRefreshEffect
    data object None : MonitoringRefreshEffect
}

/**
 * Plain-Kotlin state machine for ACTION_REFRESH_LOCATION, mirroring [MonitoringLifecycleCoordinator]'s
 * generation-token approach so it's unit-testable without Android. A refresh may only begin while
 * the service's own lifecycle is ACTIVE; [isCurrent] then re-checks both the generation (so a
 * newer refresh invalidates an older one still in flight) and the lifecycle state again (so Stop
 * or destroy invalidates a pending refresh even without an explicit [invalidate] call — that call
 * exists anyway so a late callback can never be mistaken for current, as a second, independent guard).
 */
class MonitoringRefreshCoordinator {

    private var generation = 0L

    fun onRefreshRequested(lifecycleState: MonitoringLifecycleState): MonitoringRefreshEffect {
        if (lifecycleState != MonitoringLifecycleState.ACTIVE) {
            return MonitoringRefreshEffect.None
        }
        generation++
        return MonitoringRefreshEffect.Begin(generation)
    }

    fun isCurrent(forGeneration: Long, lifecycleState: MonitoringLifecycleState): Boolean =
        lifecycleState == MonitoringLifecycleState.ACTIVE && forGeneration == generation

    fun invalidate() {
        generation++
    }
}
