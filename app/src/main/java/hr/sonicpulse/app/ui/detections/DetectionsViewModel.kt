package hr.sonicpulse.app.ui.detections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.data.remote.RemoteFailure
import hr.sonicpulse.app.data.remote.RemoteFailureClassifier
import hr.sonicpulse.app.domain.model.Detection
import hr.sonicpulse.app.domain.model.eventTime
import hr.sonicpulse.app.repository.DetectionPage
import hr.sonicpulse.app.repository.DetectionsRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.math.roundToInt

private const val PAGE_SIZE = 50

/**
 * Owns the entire Detections screen session: loaded pages, cursor, all loading/error flags,
 * selected filter, duplicate-safe merging, date grouping, and `canLoadMore`. [DetectionsRepository]
 * stays a thin single-page fetch — everything about the session lives here.
 *
 * Does **not** trigger its own initial load — [DetectionsScreen] calls [refresh] exactly once from
 * a `LaunchedEffect(Unit)` when it enters composition, so re-entering the Detections tab (the
 * ViewModel itself can survive tab navigation via the nav-graph's saved state) always re-fetches
 * a fresh first page instead of silently reusing whatever was last loaded.
 *
 * Race safety: [generation] is bumped on every [refresh] call; an in-flight page fetch (whether
 * from an older [refresh] or a superseded [loadNextPage]) only ever applies its result if the
 * generation it captured at launch is still current.
 */
@HiltViewModel
class DetectionsViewModel @Inject constructor(
    private val detectionsRepository: DetectionsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionsUiState())
    val uiState: StateFlow<DetectionsUiState> = _uiState.asStateFlow()

    private var loadedDetections: List<Detection> = emptyList()
    private var nextCursor: Long? = null
    private var canLoadMore = false
    private var hasCompletedInitialLoad = false
    private var isLoadingNextPage = false
    private var selectedFilter = DetectionsFilter.All
    private var generation = 0

    /** The in-flight first-page request, whether from [refresh]'s initial load or a manual
     * refresh. `null` once it has completed, failed, or been cancelled. Used both to cancel a
     * superseded request and to block [loadNextPage] while a refresh is active. */
    private var refreshJob: Job? = null

    /** The in-flight [loadNextPage] request, `null` once completed/failed/cancelled. */
    private var pagingJob: Job? = null

    /**
     * Before the first successful load, behaves like the initial load (nothing to preserve, full
     * -screen loading, failure is [DetectionsUiState.initialError]). After that, a manual refresh
     * preserves the currently loaded detections/cursor/canLoadMore/filter while it runs, and only
     * replaces them on success — a failure is [DetectionsUiState.refreshError], never [DetectionsUiState.initialError].
     *
     * Supersedes any in-flight paging request: [pagingJob] is cancelled outright (not just
     * discarded via the generation check) so its network call doesn't run to no purpose, and any
     * older [refreshJob] is cancelled the same way.
     */
    fun refresh() {
        generation++
        val myGeneration = generation
        val isFirstLoad = !hasCompletedInitialLoad

        // Item 13: a refresh always supersedes any in-flight loadNextPage() immediately — its
        // loading/error indicator must never linger once its result is guaranteed to be discarded.
        pagingJob?.cancel()
        isLoadingNextPage = false

        refreshJob?.cancel()

        if (isFirstLoad) {
            loadedDetections = emptyList()
            nextCursor = null
            canLoadMore = false
        }
        publishState(
            isInitialLoading = isFirstLoad,
            isRefreshing = !isFirstLoad,
            isLoadingNextPage = false,
            initialError = false,
            refreshError = false,
            pagingError = false,
            initialErrorServerConfiguration = false,
            refreshErrorServerConfiguration = false,
            pagingErrorServerConfiguration = false
        )

        val job = viewModelScope.launch {
            try {
                val page = detectionsRepository.getDetectionsPage(cursor = null, limit = PAGE_SIZE)
                if (generation != myGeneration) return@launch
                hasCompletedInitialLoad = true
                applyFirstPage(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                val failure = classifyRemoteFailure(e)
                val isServerConfiguration = failure is RemoteFailure.Unauthorized
                if (isFirstLoad) {
                    publishState(
                        isInitialLoading = false,
                        isRefreshing = false,
                        initialError = true,
                        initialErrorServerConfiguration = isServerConfiguration
                    )
                } else {
                    publishState(
                        isInitialLoading = false,
                        isRefreshing = false,
                        refreshError = true,
                        refreshErrorServerConfiguration = isServerConfiguration
                    )
                }
            }
        }
        refreshJob = job
        // Identity-safe: only clear the field if it still refers to this exact job — an older,
        // already-superseded refresh's completion must never clear a newer refresh's reference.
        job.invokeOnCompletion { if (refreshJob === job) refreshJob = null }
    }

    /** The retry action for a failed page — [nextCursor] is only ever advanced by a successful
     * [applyNextPage], so a retry after failure naturally reuses the same cursor. Blocked while
     * the initial load or a manual refresh is active, so a page fetched with a cursor that is
     * about to become stale can never merge into a freshly refreshed first page. */
    fun loadNextPage() {
        if (isLoadingNextPage || !canLoadMore || refreshJob?.isActive == true) {
            return
        }
        val cursor = nextCursor ?: return

        isLoadingNextPage = true
        val myGeneration = generation
        publishState(isLoadingNextPage = true, pagingError = false, pagingErrorServerConfiguration = false)

        val job = viewModelScope.launch {
            try {
                val page = detectionsRepository.getDetectionsPage(cursor = cursor, limit = PAGE_SIZE)
                if (generation != myGeneration) return@launch
                applyNextPage(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                isLoadingNextPage = false
                val failure = classifyRemoteFailure(e)
                val isServerConfiguration = failure is RemoteFailure.Unauthorized
                publishState(isLoadingNextPage = false, pagingError = true, pagingErrorServerConfiguration = isServerConfiguration)
            }
        }
        pagingJob = job
        // Identity-safe for the same reason as refreshJob above.
        job.invokeOnCompletion { if (pagingJob === job) pagingJob = null }
    }

    fun selectFilter(filter: DetectionsFilter) {
        selectedFilter = filter
        publishState()
    }

    /** Replaces the loaded history with a fresh first page — used by both the very first load and
     * every manual refresh. Only this clears [DetectionsUiState.refreshError]: a later successful
     * [loadNextPage] does not mean a previously failed refresh actually succeeded. */
    private fun applyFirstPage(page: DetectionPage) {
        loadedDetections = mergeDedup(emptyList(), page.items)
        nextCursor = page.nextCursor
        canLoadMore = page.nextCursor != null
        isLoadingNextPage = false
        publishState(
            isInitialLoading = false,
            isRefreshing = false,
            isLoadingNextPage = false,
            initialError = false,
            refreshError = false,
            pagingError = false,
            initialErrorServerConfiguration = false,
            refreshErrorServerConfiguration = false,
            pagingErrorServerConfiguration = false
        )
    }

    /** Merges a later page into what's already loaded. Deliberately leaves
     * [DetectionsUiState.refreshError] (and its accompanying …ServerConfiguration flag) untouched
     * — the default parameters of [publishState] carry the current values forward, so a stale
     * refresh failure stays visible until the next refresh. */
    private fun applyNextPage(page: DetectionPage) {
        loadedDetections = mergeDedup(loadedDetections, page.items)
        nextCursor = page.nextCursor
        canLoadMore = page.nextCursor != null
        isLoadingNextPage = false
        publishState(isLoadingNextPage = false, pagingError = false, pagingErrorServerConfiguration = false)
    }

    private fun publishState(
        isInitialLoading: Boolean = _uiState.value.isInitialLoading,
        isRefreshing: Boolean = _uiState.value.isRefreshing,
        isLoadingNextPage: Boolean = _uiState.value.isLoadingNextPage,
        initialError: Boolean = _uiState.value.initialError,
        refreshError: Boolean = _uiState.value.refreshError,
        pagingError: Boolean = _uiState.value.pagingError,
        initialErrorServerConfiguration: Boolean = _uiState.value.initialErrorServerConfiguration,
        refreshErrorServerConfiguration: Boolean = _uiState.value.refreshErrorServerConfiguration,
        pagingErrorServerConfiguration: Boolean = _uiState.value.pagingErrorServerConfiguration
    ) {
        val sections = groupByDate(applyFilter(loadedDetections, selectedFilter))
        val emptyState = when {
            sections.isNotEmpty() -> null
            !canLoadMore && loadedDetections.isEmpty() -> DetectionsEmptyState.NoDetectionsAtAll
            canLoadMore -> DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable
            else -> DetectionsEmptyState.NoMatchesForFilter
        }
        _uiState.value = DetectionsUiState(
            sections = sections,
            selectedFilter = selectedFilter,
            isInitialLoading = isInitialLoading,
            isRefreshing = isRefreshing,
            isLoadingNextPage = isLoadingNextPage,
            canLoadMore = canLoadMore,
            initialError = initialError,
            refreshError = refreshError,
            pagingError = pagingError,
            initialErrorServerConfiguration = initialErrorServerConfiguration,
            refreshErrorServerConfiguration = refreshErrorServerConfiguration,
            pagingErrorServerConfiguration = pagingErrorServerConfiguration,
            emptyState = emptyState
        )
    }

    private fun classifyRemoteFailure(
        throwable: Throwable
    ): RemoteFailure {
        val failure = RemoteFailureClassifier.classify(throwable)

        if (failure is RemoteFailure.Unknown) {
            Log.e(
                TAG,
                "Unexpected detections request failure",
                throwable
            )
        }

        return failure
    }

    private companion object {
        const val TAG = "DetectionsViewModel"
    }
}

/**
 * One order-preserving, duplicate-safe merge used for every page application — the very first
 * page (`existing` empty) and every later page merge alike. Keeps the first occurrence of each id
 * (existing entries first, then genuinely new ones from [incoming]), so it removes duplicates
 * inside [incoming] itself, duplicates already in [existing], and duplicates shared between the
 * two — all with a single implementation, preserving newest-to-oldest order throughout.
 */
private fun mergeDedup(existing: List<Detection>, incoming: List<Detection>): List<Detection> {
    val seen = LinkedHashSet<UUID>()
    val result = ArrayList<Detection>(existing.size + incoming.size)
    for (detection in existing) {
        if (seen.add(detection.id)) result.add(detection)
    }
    for (detection in incoming) {
        if (seen.add(detection.id)) result.add(detection)
    }
    return result
}

private fun applyFilter(detections: List<Detection>, filter: DetectionsFilter): List<Detection> = when (filter) {
    DetectionsFilter.All -> detections
    DetectionsFilter.Today -> {
        val today = LocalDate.now(ZoneId.systemDefault())
        detections.filter { it.eventTime.atZone(ZoneId.systemDefault()).toLocalDate() == today }
    }
    DetectionsFilter.Grouped -> detections.filter { it.hotspotId != null }
    DetectionsFilter.Ungrouped -> detections.filter { it.hotspotId == null }
}

/** Kotlin's `groupBy` uses a LinkedHashMap, preserving each key's first-seen order — since
 * [detections] is already newest-to-oldest by the backend's own receivedAtUtc-based pagination
 * order (deliberately never resorted by eventTime here), the newest date is, in the overwhelming
 * common case, still the first section; a rare event/received-time crossover near midnight can
 * only ever reorder entries *within* that existing sequence, never the page order itself. */
private fun groupByDate(detections: List<Detection>): List<DetectionDateSection> {
    val zone = ZoneId.systemDefault()
    return detections
        .groupBy { it.eventTime.atZone(zone).toLocalDate() }
        .map { (date, items) -> DetectionDateSection(date, items.map(::toUiModel)) }
}

private fun toUiModel(detection: Detection): DetectionHistoryItemUiModel {
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    return DetectionHistoryItemUiModel(
        id = detection.id,
        peakDbfs = detection.peakDbfs,
        listTimestampText = listTimestampTextFor(detection.eventTime, zone, locale),
        detailTimestampText = detailTimestampTextFor(detection.eventTime, zone, locale),
        receivedAtServerText = detailTimestampTextFor(detection.receivedAtUtc, zone, locale),
        latitudeText = String.format(Locale.US, "%.5f", detection.latitude),
        longitudeText = String.format(Locale.US, "%.5f", detection.longitude),
        gpsAccuracyText = detection.gpsAccuracy.roundToInt().toString(),
        grouped = detection.hotspotId != null
    )
}
