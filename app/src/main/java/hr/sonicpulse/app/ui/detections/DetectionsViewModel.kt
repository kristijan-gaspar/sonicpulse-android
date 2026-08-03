package hr.sonicpulse.app.ui.detections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.domain.model.Detection
import hr.sonicpulse.app.repository.DetectionPage
import hr.sonicpulse.app.repository.DetectionsRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
 * Triggers its one and only initial load from [init] — nothing else may call [refresh] on screen
 * entry (e.g. no Composable `LaunchedEffect(Unit) { viewModel.refresh() }`), or recomposition would
 * cause repeated backend requests.
 *
 * Race safety: [generation] is bumped on every [refresh] call; an in-flight page fetch only ever
 * applies its result if the generation it captured at launch is still current — an older refresh's
 * page arriving after a newer refresh already started can never overwrite the newer one's result.
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

    init {
        refresh()
    }

    fun refresh() {
        generation++
        val myGeneration = generation
        val isFirstLoad = !hasCompletedInitialLoad

        loadedDetections = emptyList()
        nextCursor = null
        canLoadMore = false
        isLoadingNextPage = false
        publishState(
            isInitialLoading = isFirstLoad,
            isRefreshing = !isFirstLoad,
            initialError = false,
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
                publishState(isInitialLoading = false, isRefreshing = false, initialError = true)
            }
        }
    }

    /** Also the retry action for a failed page — [nextCursor] is only ever advanced by a
     * successful [applyPage], so a retry after failure naturally reuses the same cursor. */
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
        loadedDetections = if (merge) mergeDedup(loadedDetections, page.items) else page.items
        nextCursor = page.nextCursor
        canLoadMore = page.nextCursor != null
        isLoadingNextPage = false
        publishState(isInitialLoading = false, isRefreshing = false, isLoadingNextPage = false)
    }

    private fun publishState(
        isInitialLoading: Boolean = _uiState.value.isInitialLoading,
        isRefreshing: Boolean = _uiState.value.isRefreshing,
        isLoadingNextPage: Boolean = _uiState.value.isLoadingNextPage,
        initialError: Boolean = _uiState.value.initialError,
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
            pagingError = pagingError,
            emptyState = emptyState
        )
    }
}

/** Existing ids are kept in their original (newest-to-oldest) position; only genuinely new ids
 * from [incoming] are appended, preserving order. */
private fun mergeDedup(existing: List<Detection>, incoming: List<Detection>): List<Detection> {
    val existingIds = existing.mapTo(HashSet()) { it.id }
    return existing + incoming.filter { it.id !in existingIds }
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

private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun toUiModel(detection: Detection) = DetectionHistoryItemUiModel(
    id = detection.id,
    peakDbfs = detection.peakDbfs,
    timestampText = TimestampFormatter.format(detection.receivedAtUtc),
    coordinatesText = String.format(Locale.US, "%.5f, %.5f", detection.latitude, detection.longitude),
    grouped = detection.hotspotId != null
)
