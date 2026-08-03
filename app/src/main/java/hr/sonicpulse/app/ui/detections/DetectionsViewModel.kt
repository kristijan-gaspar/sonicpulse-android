package hr.sonicpulse.app.ui.detections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.domain.model.Detection
import hr.sonicpulse.app.repository.DetectionPage
import hr.sonicpulse.app.repository.DetectionsRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /**
     * Before the first successful load, behaves like the initial load (nothing to preserve, full
     * -screen loading, failure is [DetectionsUiState.initialError]). After that, a manual refresh
     * preserves the currently loaded detections/cursor/canLoadMore/filter while it runs, and only
     * replaces them on success — a failure is [DetectionsUiState.refreshError], never [DetectionsUiState.initialError].
     */
    fun refresh() {
        generation++
        val myGeneration = generation
        val isFirstLoad = !hasCompletedInitialLoad

        // Item 13: a refresh always supersedes any in-flight loadNextPage() immediately — its
        // loading/error indicator must never linger once its result is guaranteed to be discarded.
        isLoadingNextPage = false

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
            pagingError = false
        )

        viewModelScope.launch {
            try {
                val page = detectionsRepository.getDetectionsPage(cursor = null, limit = PAGE_SIZE)
                if (generation != myGeneration) return@launch
                hasCompletedInitialLoad = true
                applyPage(page, merge = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                if (isFirstLoad) {
                    publishState(isInitialLoading = false, isRefreshing = false, initialError = true)
                } else {
                    publishState(isInitialLoading = false, isRefreshing = false, refreshError = true)
                }
            }
        }
    }

    /** The retry action for a failed page — [nextCursor] is only ever advanced by a successful
     * [applyPage], so a retry after failure naturally reuses the same cursor. */
    fun loadNextPage() {
        if (isLoadingNextPage || !canLoadMore) {
            return
        }
        isLoadingNextPage = true
        val myGeneration = generation
        publishState(isLoadingNextPage = true, pagingError = false)

        viewModelScope.launch {
            try {
                val page = detectionsRepository.getDetectionsPage(cursor = nextCursor, limit = PAGE_SIZE)
                if (generation != myGeneration) return@launch
                applyPage(page, merge = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != myGeneration) return@launch
                isLoadingNextPage = false
                publishState(isLoadingNextPage = false, pagingError = true)
            }
        }
    }

    fun selectFilter(filter: DetectionsFilter) {
        selectedFilter = filter
        publishState()
    }

    private fun applyPage(page: DetectionPage, merge: Boolean) {
        loadedDetections = mergeDedup(if (merge) loadedDetections else emptyList(), page.items)
        nextCursor = page.nextCursor
        canLoadMore = page.nextCursor != null
        isLoadingNextPage = false
        publishState(
            isInitialLoading = false,
            isRefreshing = false,
            isLoadingNextPage = false,
            initialError = false,
            refreshError = false,
            pagingError = false
        )
    }

    private fun publishState(
        isInitialLoading: Boolean = _uiState.value.isInitialLoading,
        isRefreshing: Boolean = _uiState.value.isRefreshing,
        isLoadingNextPage: Boolean = _uiState.value.isLoadingNextPage,
        initialError: Boolean = _uiState.value.initialError,
        refreshError: Boolean = _uiState.value.refreshError,
        pagingError: Boolean = _uiState.value.pagingError
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
            emptyState = emptyState
        )
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
        detections.filter { it.receivedAtUtc.atZone(ZoneId.systemDefault()).toLocalDate() == today }
    }
    DetectionsFilter.Grouped -> detections.filter { it.hotspotId != null }
    DetectionsFilter.Ungrouped -> detections.filter { it.hotspotId == null }
}

/** Kotlin's `groupBy` uses a LinkedHashMap, preserving each key's first-seen order — since
 * [detections] is already newest-to-oldest, the newest date is always the first section. */
private fun groupByDate(detections: List<Detection>): List<DetectionDateSection> {
    val zone = ZoneId.systemDefault()
    return detections
        .groupBy { it.receivedAtUtc.atZone(zone).toLocalDate() }
        .map { (date, items) -> DetectionDateSection(date, items.map(::toUiModel)) }
}

private fun toUiModel(detection: Detection): DetectionHistoryItemUiModel {
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    return DetectionHistoryItemUiModel(
        id = detection.id,
        peakDbfs = detection.peakDbfs,
        listTimestampText = listTimestampTextFor(detection.receivedAtUtc, zone, locale),
        detailTimestampText = detailTimestampTextFor(detection.receivedAtUtc, zone, locale),
        coordinatesText = String.format(Locale.US, "%.5f, %.5f", detection.latitude, detection.longitude),
        grouped = detection.hotspotId != null
    )
}
