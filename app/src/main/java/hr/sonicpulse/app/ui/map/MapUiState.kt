package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot

data class MapUiState(
    val hotspots: List<Hotspot> = emptyList(),
    /** The range the currently displayed [hotspots] actually belong to. Only a *successful* load
     * ever changes this — a failed range change reverts to whatever was committed before it. */
    val committedRange: HotspotTimeRange = HotspotTimeRange.Last24Hours,
    /** Non-null only while a *different* range than [committedRange] is loading — the UI shows
     * this range as selected (with a loading indicator) while [hotspots] still reflects
     * [committedRange] until the new range succeeds. Always null during a manual refresh of the
     * already-committed range. */
    val pendingRange: HotspotTimeRange? = null,
    /** True only for the very first load, before any load has ever completed successfully. */
    val isInitialLoading: Boolean = true,
    /** True for a manual refresh or a range change after at least one load already succeeded. */
    val isLoadingSubsequent: Boolean = false,
    /** The very first load failed — full-screen retry state, nothing to show yet. */
    val initialError: Boolean = false,
    /** A manual refresh or range change (after at least one load already succeeded) failed — the
     * previously loaded hotspots and committed range are preserved unchanged. Never set together
     * with [initialError]. */
    val subsequentError: Boolean = false
)
