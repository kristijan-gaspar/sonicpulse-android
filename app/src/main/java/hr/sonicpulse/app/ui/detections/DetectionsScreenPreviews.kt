package hr.sonicpulse.app.ui.detections

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import hr.sonicpulse.app.ui.theme.SonicPulseTheme
import java.time.LocalDate
import java.util.UUID

/** Previews of [DetectionsContent] only — the stateless presentation layer, same pattern as
 * [hr.sonicpulse.app.ui.monitoring.MonitoringContent]'s previews. */

private fun previewItem(peakDbfs: Double, grouped: Boolean) = DetectionHistoryItemUiModel(
    id = UUID.randomUUID(),
    peakDbfs = peakDbfs,
    timestampText = "14:32:07",
    coordinatesText = "45.80000, 16.00000",
    grouped = grouped
)

@Preview(name = "Loading", showBackground = true)
@Composable
private fun DetectionsContentLoadingPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(isInitialLoading = true),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "List with sections", showBackground = true)
@Composable
private fun DetectionsContentListPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(
                isInitialLoading = false,
                canLoadMore = true,
                sections = listOf(
                    DetectionDateSection(
                        date = LocalDate.now(),
                        items = listOf(previewItem(-9.4, grouped = true), previewItem(-14.2, grouped = false))
                    ),
                    DetectionDateSection(
                        date = LocalDate.now().minusDays(1),
                        items = listOf(previewItem(-11.0, grouped = false))
                    )
                )
            ),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "Empty — no detections at all", showBackground = true)
@Composable
private fun DetectionsContentEmptyNoHistoryPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(
                isInitialLoading = false,
                emptyState = DetectionsEmptyState.NoDetectionsAtAll
            ),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "Empty — no matches for filter", showBackground = true)
@Composable
private fun DetectionsContentEmptyNoMatchesPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(
                isInitialLoading = false,
                selectedFilter = DetectionsFilter.Grouped,
                emptyState = DetectionsEmptyState.NoMatchesForFilter
            ),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "Empty — no current matches, more pages available", showBackground = true)
@Composable
private fun DetectionsContentEmptyMoreAvailablePreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(
                isInitialLoading = false,
                selectedFilter = DetectionsFilter.Grouped,
                canLoadMore = true,
                emptyState = DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable
            ),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "Initial load error", showBackground = true)
@Composable
private fun DetectionsContentInitialErrorPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(isInitialLoading = false, initialError = true),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}

@Preview(name = "Next-page error footer", showBackground = true)
@Composable
private fun DetectionsContentPagingErrorPreview() {
    SonicPulseTheme {
        DetectionsContent(
            uiState = DetectionsUiState(
                isInitialLoading = false,
                canLoadMore = true,
                pagingError = true,
                sections = listOf(
                    DetectionDateSection(date = LocalDate.now(), items = listOf(previewItem(-9.4, grouped = true)))
                )
            ),
            onSelectFilter = { }, onLoadNextPage = { }, onRefresh = { }
        )
    }
}
