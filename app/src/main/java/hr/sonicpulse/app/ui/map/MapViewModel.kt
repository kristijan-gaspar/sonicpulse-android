package hr.sonicpulse.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.data.datastore.PermissionRequestHistory
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.repository.HotspotsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns only backend hotspot state for the Map screen: loaded hotspots, committed/pending range,
 * loading/error flags, the active request job and the stale-result generation token. Camera state,
 * MapLibre style state, the location puck and tile/style loading state are screen (Composable)
 * concerns — mirrors [hr.sonicpulse.app.ui.detections.DetectionsViewModel]'s split with
 * [hr.sonicpulse.app.repository.DetectionsRepository].
 *
 * Does **not** fetch from `init` — [MapScreen] calls [onScreenEntered] exactly once from a
 * `LaunchedEffect(Unit)` when it enters composition, so re-entering the Map tab always re-fetches
 * a fresh result for the currently committed range instead of silently reusing stale data.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val hotspotsRepository: HotspotsRepository,
    private val permissionRequestHistory: PermissionRequestHistory
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Reused from Monitoring's identical need — see [PermissionRequestHistory] for why Android
     * itself doesn't expose this. Shared here rather than duplicated so the current-location
     * button's permanent-denial classification uses the exact same history as every other
     * permission flow in the app. */
    suspend fun hasRequestedPermissionBefore(permission: String): Boolean =
        permissionRequestHistory.hasRequestedBefore(permission)

    suspend fun markPermissionRequested(permission: String) {
        permissionRequestHistory.markRequested(permission)
    }

    private var loadedHotspots: List<Hotspot> = emptyList()
    private var committedRange: HotspotTimeRange = HotspotTimeRange.Last24Hours
    private var hasCompletedInitialLoad = false
    private var generation = 0
    private var loadJob: Job? = null

    /** The one and only screen-entry trigger — always reloads [committedRange] (24 hours on the
     * very first entry), even if the ViewModel survived a previous visit via saved nav state. */
    fun onScreenEntered() {
        load(range = committedRange, isRangeChange = false)
    }

    /** Re-fetches the currently committed range. A no-op-safe user action: at most one request per
     * call, and it always supersedes whatever request (refresh or range change) was in flight. */
    fun refresh() {
        load(range = committedRange, isRangeChange = false)
    }

    /** Selecting the already-committed or already-pending range is a deliberate no-op — one user
     * action must never produce more than one request, and re-tapping the same chip isn't a new
     * action. */
    fun selectRange(range: HotspotTimeRange) {
        if (range == committedRange || range == _uiState.value.pendingRange) {
            return
        }
        load(range = range, isRangeChange = true)
    }

    private fun load(range: HotspotTimeRange, isRangeChange: Boolean) {
        generation++
        val myGeneration = generation
        val isFirstLoad = !hasCompletedInitialLoad

        // Supersedes any in-flight load outright — cancels its network call, not just its result.
        loadJob?.cancel()

        publishState(
            isInitialLoading = isFirstLoad,
            isLoadingSubsequent = !isFirstLoad,
            pendingRange = if (!isFirstLoad && isRangeChange) range else null,
            initialError = false,
            subsequentError = false
        )

        val job = viewModelScope.launch {
            try {
                val hotspots = hotspotsRepository.getHotspots(sinceHours = range.hours)
                if (generation != myGeneration) return@launch
                hasCompletedInitialLoad = true
                loadedHotspots = hotspots
                committedRange = range
                publishState(
                    isInitialLoading = false,
                    isLoadingSubsequent = false,
                    pendingRange = null,
                    initialError = false,
                    subsequentError = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                // committedRange/loadedHotspots are untouched on failure — a failed range change
                // therefore automatically "reverts" simply by never having been committed.
                if (isFirstLoad) {
                    publishState(
                        isInitialLoading = false,
                        isLoadingSubsequent = false,
                        pendingRange = null,
                        initialError = true
                    )
                } else {
                    publishState(
                        isInitialLoading = false,
                        isLoadingSubsequent = false,
                        pendingRange = null,
                        subsequentError = true
                    )
                }
            }
        }
        loadJob = job
        // Identity-safe: only clear the field if it still refers to this exact job — an older,
        // already-superseded load's completion must never clear a newer load's reference.
        job.invokeOnCompletion { if (loadJob === job) loadJob = null }
    }

    private fun publishState(
        isInitialLoading: Boolean,
        isLoadingSubsequent: Boolean,
        pendingRange: HotspotTimeRange?,
        initialError: Boolean = false,
        subsequentError: Boolean = false
    ) {
        _uiState.value = MapUiState(
            hotspots = loadedHotspots,
            committedRange = committedRange,
            pendingRange = pendingRange,
            isInitialLoading = isInitialLoading,
            isLoadingSubsequent = isLoadingSubsequent,
            initialError = initialError,
            subsequentError = subsequentError
        )
    }
}
