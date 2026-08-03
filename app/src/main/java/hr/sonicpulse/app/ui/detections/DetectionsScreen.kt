package hr.sonicpulse.app.ui.detections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.ui.components.AppCard
import hr.sonicpulse.app.ui.components.FilterChipRow
import hr.sonicpulse.app.ui.components.SectionHeader
import hr.sonicpulse.app.ui.components.StatusBadge
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun DetectionsScreen(viewModel: DetectionsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The one and only screen-entry trigger — DetectionsViewModel itself never auto-loads (see its
    // KDoc). Unit never changes, so this fires exactly once per composition of this screen; since
    // NavHost disposes/recomposes a destination's content on each visit, leaving the Detections tab
    // and returning to it re-runs this effect and fetches a fresh first page, even though the
    // ViewModel instance itself (and its state) can survive the round trip via saved nav state.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    DetectionsContent(
        uiState = uiState,
        onSelectFilter = viewModel::selectFilter,
        onLoadNextPage = viewModel::loadNextPage,
        onRefresh = viewModel::refresh
    )
}

@Composable
internal fun DetectionsContent(
    uiState: DetectionsUiState,
    onSelectFilter: (DetectionsFilter) -> Unit,
    onLoadNextPage: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDetection by remember { mutableStateOf<DetectionHistoryItemUiModel?>(null) }

    // Resolved here (a @Composable context) so the label lambda passed below can stay a plain,
    // non-@Composable (T) -> String — FilterChipRow is a shared component with no @Composable
    // dependency on any one screen's string resources.
    val allLabel = stringResource(R.string.filter_all)
    val todayLabel = stringResource(R.string.filter_today)
    val groupedLabel = stringResource(R.string.filter_grouped)
    val ungroupedLabel = stringResource(R.string.filter_ungrouped)

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChipRow(
                options = DetectionsFilter.entries,
                selected = uiState.selectedFilter,
                onSelect = onSelectFilter,
                label = { filter ->
                    when (filter) {
                        DetectionsFilter.All -> allLabel
                        DetectionsFilter.Today -> todayLabel
                        DetectionsFilter.Grouped -> groupedLabel
                        DetectionsFilter.Ungrouped -> ungroupedLabel
                    }
                },
                modifier = Modifier.weight(1f).padding(vertical = Spacing.sm)
            )
            IconButton(
                onClick = onRefresh,
                enabled = !uiState.isInitialLoading && !uiState.isRefreshing
            ) {
                if (uiState.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
        }

        if (uiState.refreshError) {
            RefreshErrorBanner(onRetry = onRefresh)
        }

        when {
            uiState.isInitialLoading -> FullScreenLoading()
            uiState.initialError -> FullScreenError(onRetry = onRefresh)
            uiState.emptyState != null -> DetectionsEmptyView(
                emptyState = uiState.emptyState,
                isLoadingNextPage = uiState.isLoadingNextPage,
                pagingError = uiState.pagingError,
                onLoadMore = onLoadNextPage
            )
            else -> DetectionsList(
                sections = uiState.sections,
                isLoadingNextPage = uiState.isLoadingNextPage,
                canLoadMore = uiState.canLoadMore,
                pagingError = uiState.pagingError,
                onLoadNextPage = onLoadNextPage,
                onItemClick = { selectedDetection = it }
            )
        }
    }

    selectedDetection?.let { detection ->
        DetectionDetailBottomSheet(detection = detection, onDismiss = { selectedDetection = null })
    }
}

@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.detections_error_refresh),
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.Warning
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetectionsList(
    sections: List<DetectionDateSection>,
    isLoadingNextPage: Boolean,
    canLoadMore: Boolean,
    pagingError: Boolean,
    onLoadNextPage: () -> Unit,
    onItemClick: (DetectionHistoryItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    // Paging is exclusively an explicit user action (the footer's Load more/Retry) — no
    // scroll-position-triggered auto-fetch. That avoids retrying a failed page merely because the
    // footer relayouts near the end of the list, and avoids silently walking the entire remaining
    // history while a restrictive filter leaves only a handful of rows visible per page.
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg)) {
        sections.forEach { section ->
            stickyHeader { DateSectionHeader(section.date) }
            items(section.items, key = { it.id.toString() }) { item ->
                DetectionListItem(
                    item = item,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
            }
        }
        item {
            PagingFooter(
                isLoadingNextPage = isLoadingNextPage,
                pagingError = pagingError,
                canLoadMore = canLoadMore,
                onLoadNextPage = onLoadNextPage
            )
        }
    }
}

/** One deterministic footer: exactly one of loading / error / load-more / nothing, per current
 * state — never more than one of these at once, and never a retry that fires automatically. */
@Composable
private fun PagingFooter(
    isLoadingNextPage: Boolean,
    pagingError: Boolean,
    canLoadMore: Boolean,
    onLoadNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoadingNextPage -> LoadingFooter(modifier)
        pagingError -> PagingErrorFooter(onRetry = onLoadNextPage, modifier = modifier)
        canLoadMore -> LoadMoreFooter(onClick = onLoadNextPage, modifier = modifier)
    }
}

@Composable
private fun LoadMoreFooter(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) { Text(stringResource(R.string.action_load_more)) }
    }
}

@Composable
private fun DateSectionHeader(date: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now(ZoneId.systemDefault())
    val headerDate = sectionHeaderDateFor(date, today, Locale.getDefault())
    val text = when (headerDate) {
        is SectionHeaderDate.Today -> stringResource(R.string.section_today_with_date, headerDate.shortDate)
        is SectionHeaderDate.OtherDate -> headerDate.longDate
    }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        SectionHeader(text = text, modifier = Modifier.padding(vertical = Spacing.sm))
    }
}

@Composable
private fun DetectionListItem(
    item: DetectionHistoryItemUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconBackground = if (item.grouped) SemanticColors.SuccessBg else SemanticColors.WarningBg
    val iconTint = if (item.grouped) SemanticColors.Success else SemanticColors.Warning
    val icon = if (item.grouped) Icons.Filled.CheckCircle else Icons.Filled.Schedule

    AppCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(iconBackground, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "%.1f".format(item.peakDbfs),
                        style = MonospaceValueStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = item.listTimestampText,
                        style = MonospaceValueStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.coordinatesText,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    StatusBadge(grouped = item.grouped)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun DetectionsEmptyView(
    emptyState: DetectionsEmptyState,
    isLoadingNextPage: Boolean,
    pagingError: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textRes = when (emptyState) {
        DetectionsEmptyState.NoDetectionsAtAll -> R.string.detections_empty_no_history
        DetectionsEmptyState.NoMatchesForFilter -> R.string.detections_empty_no_matches
        DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable -> R.string.detections_empty_no_matches_more_available
    }
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
        if (emptyState is DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable) {
            Spacer(modifier = Modifier.height(Spacing.md))
            when {
                isLoadingNextPage -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                pagingError -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.detections_error_paging),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    TextButton(onClick = onLoadMore) { Text(stringResource(R.string.action_retry)) }
                }
                else -> TextButton(onClick = onLoadMore) { Text(stringResource(R.string.action_load_more)) }
            }
        }
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FullScreenError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.detections_error_initial_load),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun LoadingFooter(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PagingErrorFooter(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.detections_error_paging),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionDetailBottomSheet(
    detection: DetectionHistoryItemUiModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = AppShapes.BottomSheet) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detection_detail_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DetailCell(value = "%.1f".format(detection.peakDbfs), modifier = Modifier.weight(1f))
                DetailCell(
                    value = stringResource(if (detection.grouped) R.string.status_grouped else R.string.status_ungrouped),
                    color = if (detection.grouped) SemanticColors.Success else SemanticColors.Warning,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            DetailRow(label = stringResource(R.string.detail_label_timestamp), value = detection.detailTimestampText)
            DetailRow(label = stringResource(R.string.detail_label_coordinates), value = detection.coordinatesText)
        }
    }
}

@Composable
private fun DetailCell(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(modifier = modifier, shape = AppShapes.ChipOrBadge, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(modifier = Modifier.padding(Spacing.md), contentAlignment = Alignment.Center) {
            Text(text = value, style = MonospaceValueStyle.copy(fontWeight = FontWeight.Bold), color = color)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(text = value, style = MonospaceValueStyle)
    }
}
