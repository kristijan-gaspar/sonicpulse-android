package hr.sonicpulse.app.ui.detections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.util.UUID
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

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
    // Only the id is retained, never the tapped-at-that-moment DetectionHistoryItemUiModel itself —
    // the sheet below re-derives its content from the current uiState.sections on every
    // recomposition (selectedDetectionFrom), so a refresh/filter/paging change that drops this item
    // closes the sheet instead of continuing to show a stale snapshot. Mirrors MapScreen's
    // HotspotSheet.Detail + selectedHotspotFrom for the same reason.
    var selectedDetectionId by remember { mutableStateOf<UUID?>(null) }

    LaunchedEffect(uiState.sections, selectedDetectionId) {
        val currentId = selectedDetectionId
        if (currentId != null && selectedDetectionFrom(uiState.sections, currentId) == null) {
            selectedDetectionId = null
        }
    }

    val allLabel = stringResource(R.string.filter_all)
    val todayLabel = stringResource(R.string.filter_today)
    val groupedLabel = stringResource(R.string.filter_grouped)
    val ungroupedLabel = stringResource(R.string.filter_ungrouped)

    Column(modifier = modifier.fillMaxSize()) {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm)
        )

        if (uiState.refreshError) {
            RefreshErrorBanner(
                onRetry = onRefresh,
                serverConfigurationError = uiState.refreshErrorServerConfiguration
            )
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                uiState.isInitialLoading -> FullScreenLoading()
                uiState.initialError ->
                    FullScreenError(
                        onRetry = onRefresh,
                        serverConfigurationError = uiState.initialErrorServerConfiguration
                    )

                uiState.emptyState != null -> DetectionsEmptyView(
                    emptyState = uiState.emptyState,
                    isLoadingNextPage = uiState.isLoadingNextPage,
                    pagingError = uiState.pagingError,
                    pagingErrorServerConfiguration = uiState.pagingErrorServerConfiguration,
                    pagingActionDisabled = uiState.isRefreshing,
                    onLoadMore = onLoadNextPage
                )

                else -> DetectionsList(
                    sections = uiState.sections,
                    isLoadingNextPage = uiState.isLoadingNextPage,
                    canLoadMore = uiState.canLoadMore,
                    pagingError = uiState.pagingError,
                    pagingErrorServerConfiguration = uiState.pagingErrorServerConfiguration,
                    pagingActionDisabled = uiState.isRefreshing,
                    onLoadNextPage = onLoadNextPage,
                    onItemClick = { selectedDetectionId = it.id }
                )
            }
        }

        // Re-derived from the latest uiState.sections on every recomposition, not the stale
        // snapshot captured when the sheet was opened — see selectedDetectionId's own comment.
        selectedDetectionFrom(uiState.sections, selectedDetectionId)?.let { detection ->
            DetectionDetailBottomSheet(
                detection = detection,
                onDismiss = { selectedDetectionId = null })
        }
    }
}

/**
 * Re-derives the selected detection from the currently loaded [sections] by id, rather than
 * trusting a captured object from the moment it was tapped — so an open detail sheet always
 * reflects the latest data for that detection, and closes on its own (returning null) once the
 * detection is no longer present (evicted by a refresh, paged out, or filtered out by a filter
 * change), instead of continuing to display a stale snapshot. `selectedId == null` (nothing
 * selected) also returns null. Mirrors `hr.sonicpulse.app.ui.map.selectedHotspotFrom`.
 */
internal fun selectedDetectionFrom(
    sections: List<DetectionDateSection>,
    selectedId: UUID?
): DetectionHistoryItemUiModel? =
    selectedId?.let { id -> sections.asSequence().flatMap { it.items }.find { it.id == id } }

/** The message is a full sentence, not a short label — an unweighted Row with SpaceBetween would
 * let it collide with the Retry button instead of wrapping at larger font scales/narrower widths.
 * Weighting the message bounds it to whatever width remains after the button's own fixed size. */
@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier, serverConfigurationError: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                if (serverConfigurationError) R.string.error_server_configuration else R.string.detections_error_refresh
            ),
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.Warning,
            modifier = Modifier.weight(1f)
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
    pagingErrorServerConfiguration: Boolean,
    pagingActionDisabled: Boolean,
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
            items(section.items, key = { it.id }) { item ->
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
                pagingErrorServerConfiguration = pagingErrorServerConfiguration,
                canLoadMore = canLoadMore,
                pagingActionDisabled = pagingActionDisabled,
                onLoadNextPage = onLoadNextPage
            )
        }
    }
}

/** One deterministic footer: exactly one of loading / error / load-more / nothing, per current
 * state — never more than one of these at once, and never a retry that fires automatically.
 * While [pagingActionDisabled] (a manual refresh is active), the footer shows nothing at all: the
 * ViewModel already refuses to issue a paging request during a refresh, and a visible but inert
 * Load more/Retry would be misleading. */
@Composable
private fun PagingFooter(
    isLoadingNextPage: Boolean,
    pagingError: Boolean,
    pagingErrorServerConfiguration: Boolean,
    canLoadMore: Boolean,
    pagingActionDisabled: Boolean,
    onLoadNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        pagingActionDisabled -> Unit
        isLoadingNextPage -> LoadingFooter(modifier)
        pagingError -> PagingErrorFooter(
            onRetry = onLoadNextPage,
            serverConfigurationError = pagingErrorServerConfiguration,
            modifier = modifier
        )
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
                    .background(iconBackground, AppShapes.IconContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                // FlowRow, not Row: at a narrow width combined with a larger system font scale, the
                // peak level + timestamp (and, below, coordinates + status badge) can together
                // exceed the available width — an unweighted Row with SpaceBetween would let them
                // overlap instead of clipping. Wrapping the second element onto its own line reads
                // fine and costs nothing at the normal single-line width/scale.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.peak_level_dbfs, item.peakDbfs),
                        style = MonospaceValueStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = item.listTimestampText,
                        style = MonospaceValueStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.coordinates_format, item.latitudeText, item.longitudeText),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    StatusBadge(grouped = item.grouped, modifier = Modifier.align(Alignment.CenterVertically))
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
    pagingErrorServerConfiguration: Boolean,
    pagingActionDisabled: Boolean,
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
        if (emptyState is DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable && !pagingActionDisabled) {
            Spacer(modifier = Modifier.height(Spacing.md))
            when {
                isLoadingNextPage -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                pagingError -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            if (pagingErrorServerConfiguration) {
                                R.string.error_server_configuration
                            } else {
                                R.string.detections_error_paging
                            }
                        ),
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
private fun FullScreenError(onRetry: () -> Unit, modifier: Modifier = Modifier, serverConfigurationError: Boolean = false) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(
                if (serverConfigurationError) R.string.error_server_configuration else R.string.detections_error_initial_load
            ),
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
private fun PagingErrorFooter(onRetry: () -> Unit, modifier: Modifier = Modifier, serverConfigurationError: Boolean = false) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                if (serverConfigurationError) R.string.error_server_configuration else R.string.detections_error_paging
            ),
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
        // verticalScroll: at a compact screen height combined with a large system font scale, this
        // sheet's content (Signal/Location/Time/Grouping sections) can exceed what ModalBottomSheet
        // is able to show at once — without this, the excess would simply be clipped and
        // unreachable rather than scrollable.
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f): a long localized title must wrap instead of pushing the close button
                // out of the row (and off-sheet) at narrow widths or larger font scales.
                Text(
                    text = stringResource(R.string.detection_detail_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))

            SectionHeader(text = stringResource(R.string.section_signal))
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DetailCell(
                    label = stringResource(R.string.detail_label_peak_level),
                    value = stringResource(R.string.peak_level_dbfs, detection.peakDbfs),
                    modifier = Modifier.weight(1f)
                )
                DetailCell(
                    label = stringResource(R.string.detail_label_status),
                    value = stringResource(if (detection.grouped) R.string.status_grouped else R.string.status_ungrouped),
                    color = if (detection.grouped) SemanticColors.Success else SemanticColors.Warning,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))
            SectionHeader(text = stringResource(R.string.section_location))
            Spacer(modifier = Modifier.height(Spacing.sm))
            DetailRow(label = stringResource(R.string.detail_label_latitude), value = detection.latitudeText)
            DetailRow(label = stringResource(R.string.detail_label_longitude), value = detection.longitudeText)
            DetailRow(
                label = stringResource(R.string.detail_label_gps_accuracy),
                value = stringResource(R.string.gps_accuracy_format, detection.gpsAccuracyText)
            )

            Spacer(modifier = Modifier.height(Spacing.md))
            SectionHeader(text = stringResource(R.string.section_time))
            Spacer(modifier = Modifier.height(Spacing.sm))
            DetailRow(label = stringResource(R.string.detail_label_detected), value = detection.detailTimestampText)
            DetailRow(label = stringResource(R.string.detail_label_received_server), value = detection.receivedAtServerText)

            Spacer(modifier = Modifier.height(Spacing.md))
            SectionHeader(text = stringResource(R.string.section_grouping))
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(if (detection.grouped) R.string.grouping_assigned else R.string.grouping_not_assigned),
                style = MaterialTheme.typography.bodyMedium,
                color = if (detection.grouped) SemanticColors.Success else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun DetailCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(modifier = modifier, shape = AppShapes.ChipOrBadge, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(text = value, style = MonospaceValueStyle.copy(fontWeight = FontWeight.Bold), color = color)
        }
    }
}

/** FlowRow, not Row: weighting only [value] (a prior version of this fix) still let a long
 * localized [label] alone consume most of the width, since an unweighted sibling is otherwise
 * measured at its full natural width with no upper bound. FlowRow constrains *every* child to at
 * most the row's own width, so either one wraps onto its own line(s) — never shrunk or clipped —
 * the instant it doesn't fit; when both fit on one line, SpaceBetween still places [label] at the
 * start and [value] at the end exactly as the plain Row did. */
@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(text = value, style = MonospaceValueStyle)
    }
}
