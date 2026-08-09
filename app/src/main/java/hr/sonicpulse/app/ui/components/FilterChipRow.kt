package hr.sonicpulse.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** How far towards [MaterialTheme.colorScheme].primary the disabled+selected chip container is
 * blended from surfaceVariant — an opaque blend (not alpha-over-background) so the chip stays
 * fully readable when a [FilterChipRow] is drawn as a floating overlay (e.g. over the Map screen's
 * tiles) rather than on a flat background. */
private const val DisabledSelectedContainerBlend = 0.45f

/** [FilterChipRow]'s own `LazyRow` content padding before its first chip — public so a caller
 * whose other overlays must visually align with the first chip's left edge (e.g. the Map screen's
 * legend, below its [FilterChipRow]) can reuse this exact value instead of duplicating it as an
 * unexplained magic number that could later drift out of sync. Also the horizontal inset
 * [ResponsiveFilterRow]'s compact fallback applies, for the same alignment reason. */
val FilterChipRowHorizontalContentPadding = 16.dp

/** A horizontally scrollable, single-select row of Material 3 FilterChips — a shared component
 * (design doc §4.4), not specific to any one screen's filter set. [enabled] and [loading] default
 * to "always enabled, never loading" so existing callers (e.g. Detections) are unaffected; the Map
 * screen's range chips are the first caller to actually use them (a chip mid-request shows a small
 * spinner in place of its label and is disabled while any competing request is active).
 *
 * Every chip state (normal/selected/disabled/disabled-selected) gets an explicit, theme-aware
 * color rather than relying on Material3's own FilterChip defaults, whose disabled colors are too
 * low-contrast against this app's dark theme background to read as anything but invisible. Note
 * Material3's [SelectableChipColors] has only one `disabledLabelColor` slot — it is shared between
 * disabled-unselected and disabled-selected (there is no separate "disabled selected label" color
 * to set); the two disabled states are told apart by their container and border instead.
 *
 * Still scrollable on its own (a safety net if it's ever reached with content that doesn't fit),
 * but callers that want a compact single-selector fallback instead of a partially clipped-looking
 * scrollable row at large font scales/narrow widths should use [ResponsiveFilterRow] instead. */
@Composable
fun <T> FilterChipRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    loading: (T) -> Boolean = { false }
) {
    val colors = filterChipColors()

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = FilterChipRowHorizontalContentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { option ->
            FilterChipItem(
                option = option,
                isSelected = option == selected,
                isEnabled = enabled(option),
                isLoading = loading(option),
                onSelect = onSelect,
                label = label(option),
                colors = colors
            )
        }
    }
}

/** One chip's full visual/interaction shape, factored out of [FilterChipRow] so
 * [ResponsiveFilterRow] can render the exact same chip — same label/loading/disabled presentation,
 * same colors and border rules — both in its real (LazyRow) content and in the plain, unconstrained
 * [Row] it measures to decide whether that content actually fits. */
@Composable
internal fun <T> FilterChipItem(
    option: T,
    isSelected: Boolean,
    isEnabled: Boolean,
    isLoading: Boolean,
    onSelect: (T) -> Unit,
    label: String,
    colors: SelectableChipColors
) {
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(option) },
        enabled = isEnabled,
        label = {
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(label)
                }
            } else {
                Text(label)
            }
        },
        colors = colors,
        border = filterChipBorder(isEnabled, isSelected)
    )
}

@Composable
internal fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    disabledSelectedContainerColor = lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.primary,
        DisabledSelectedContainerBlend
    )
)

@Composable
internal fun filterChipBorder(isEnabled: Boolean, isSelected: Boolean): BorderStroke {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    return FilterChipDefaults.filterChipBorder(
        enabled = isEnabled,
        selected = isSelected,
        borderColor = outline,
        selectedBorderColor = Color.Transparent,
        disabledBorderColor = outline.copy(alpha = 0.7f),
        disabledSelectedBorderColor = primary.copy(alpha = 0.6f)
    )
}
