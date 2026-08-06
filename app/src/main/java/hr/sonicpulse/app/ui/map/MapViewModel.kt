package hr.sonicpulse.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.data.datastore.PermissionRequestHistory
import hr.sonicpulse.app.data.remote.RemoteFailure
import hr.sonicpulse.app.data.remote.RemoteFailureClassifier
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.repository.HotspotsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which operation a failed subsequent (post-initial) load was — so [MapViewModel.retry] repeats
 * the operation that actually failed, rather than always falling back to a plain refresh. */
sealed interface FailedMapRequest {
    data object Refresh : FailedMapRequest
    data class RangeChange(val range: HotspotTimeRange) : FailedMapRequest
}

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

    /** True only while the very first load (whether from [onScreenEntered] or a [refresh]/[retry]
     * repeating it after an initial failure) is actually in flight. Guards [refresh], [selectRange]
     * and [retry] — not [load] itself — so a competing user action during initial load is refused
     * outright rather than cancelling the request the whole screen is waiting on. */
    private var isInitialRequestActive = false

    /** What the last *subsequent* (post-initial) load attempt was, if it failed and hasn't been
     * superseded by a newer explicit user action yet. `null` whenever there is nothing to retry —
     * including right after a successful load, and right after starting any new load (a fresh
     * attempt discards whatever failure preceded it, win or lose). */
    private var failedRequest: FailedMapRequest? = null

    /** The one and only screen-entry trigger — always reloads [committedRange] (24 hours on the
     * very first entry), even if the ViewModel survived a previous visit via saved nav state. */
    fun onScreenEntered() {
        load(range = committedRange, isRangeChange = false)
    }

    /** Re-fetches the currently committed range. Refused outright while the initial load is still
     * in flight (see [isInitialRequestActive]) — otherwise a no-op-safe user action: at most one
     * request per call, and it supersedes whatever subsequent request was in flight. */
    fun refresh() {
        if (isInitialRequestActive) return
        load(range = committedRange, isRangeChange = false)
    }

    /** Selecting the already-committed or already-pending range is a deliberate no-op — one user
     * action must never produce more than one request, and re-tapping the same chip isn't a new
     * action. Also refused while the initial load is in flight, same as [refresh]. */
    fun selectRange(range: HotspotTimeRange) {
        if (isInitialRequestActive) return
        if (range == committedRange || range == _uiState.value.pendingRange) {
            return
        }
        load(range = range, isRangeChange = true)
    }

    /** Repeats whichever operation [failedRequest] actually was — a plain refresh of the committed
     * range, or the specific range that failed to load — never a blind fallback to the committed
     * range. A no-op if there is nothing recorded to retry (e.g. the failure was already superseded
     * by a newer explicit action, or nothing has failed). Refused while the initial load is in
     * flight, same as [refresh]/[selectRange]. */
    fun retry() {
        if (isInitialRequestActive) return
        when (val failed = failedRequest) {
            null -> Unit
            FailedMapRequest.Refresh -> load(range = committedRange, isRangeChange = false)
            is FailedMapRequest.RangeChange -> load(range = failed.range, isRangeChange = true)
        }
    }

    private fun load(range: HotspotTimeRange, isRangeChange: Boolean) {
        generation++
        val myGeneration = generation
        val isFirstLoad = !hasCompletedInitialLoad
        if (isFirstLoad) {
            isInitialRequestActive = true
        }

        // Supersedes any in-flight load outright — cancels its network call, not just its result.
        // Any load attempt — whether a fresh user action or a retry of a previous failure —
        // discards the old failure record; it is only restored if this new attempt fails too.
        loadJob?.cancel()
        failedRequest = null

        publishState(
            isInitialLoading = isFirstLoad,
            isLoadingSubsequent = !isFirstLoad,
            pendingRange = if (!isFirstLoad && isRangeChange) range else null,
            initialError = false,
            subsequentError = false,
            initialErrorServerConfiguration = false,
            subsequentErrorServerConfiguration = false
        )

        val job = viewModelScope.launch {
            try {
                val hotspots = hotspotsRepository.getHotspots(sinceHours = range.hours)
                if (generation != myGeneration) return@launch
                hasCompletedInitialLoad = true
                if (isFirstLoad) {
                    isInitialRequestActive = false
                }
                loadedHotspots = hotspots
                committedRange = range
                publishState(
                    isInitialLoading = false,
                    isLoadingSubsequent = false,
                    pendingRange = null,
                    initialError = false,
                    subsequentError = false,
                    initialErrorServerConfiguration = false,
                    subsequentErrorServerConfiguration = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                val isServerConfiguration = RemoteFailureClassifier.classify(e) is RemoteFailure.Unauthorized
                // committedRange/loadedHotspots are untouched on failure — a failed range change
                // therefore automatically "reverts" simply by never having been committed.
                if (isFirstLoad) {
                    isInitialRequestActive = false
                    publishState(
                        isInitialLoading = false,
                        isLoadingSubsequent = false,
                        pendingRange = null,
                        initialError = true,
                        initialErrorServerConfiguration = isServerConfiguration
                    )
                } else {
                    failedRequest = if (isRangeChange) FailedMapRequest.RangeChange(range) else FailedMapRequest.Refresh
                    publishState(
                        isInitialLoading = false,
                        isLoadingSubsequent = false,
                        pendingRange = null,
                        subsequentError = true,
                        subsequentErrorServerConfiguration = isServerConfiguration
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
        subsequentError: Boolean = false,
        initialErrorServerConfiguration: Boolean = false,
        subsequentErrorServerConfiguration: Boolean = false
    ) {
        _uiState.value = MapUiState(
            hotspots = loadedHotspots,
            committedRange = committedRange,
            pendingRange = pendingRange,
            isInitialLoading = isInitialLoading,
            isLoadingSubsequent = isLoadingSubsequent,
            initialError = initialError,
            subsequentError = subsequentError,
            initialErrorServerConfiguration = initialErrorServerConfiguration,
            subsequentErrorServerConfiguration = subsequentErrorServerConfiguration
        )
    }
}
