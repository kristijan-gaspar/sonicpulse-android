package hr.sonicpulse.app.ui.detections

data class DetectionsUiState(
    val sections: List<DetectionDateSection> = emptyList(),
    val selectedFilter: DetectionsFilter = DetectionsFilter.All,
    /** True only for the very first load, before any page has ever loaded successfully. */
    val isInitialLoading: Boolean = true,
    /** True for a manually triggered reload after at least one page already loaded successfully. */
    val isRefreshing: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val canLoadMore: Boolean = false,
    /** The very first load failed — full-screen error, nothing to show yet. */
    val initialError: Boolean = false,
    /** A loadNextPage() attempt failed — already-loaded items are preserved; a retry reuses the
     * same cursor (loadNextPage() never advances the cursor on failure). */
    val pagingError: Boolean = false,
    val emptyState: DetectionsEmptyState? = null
)
