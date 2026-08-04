package hr.sonicpulse.app.ui.components

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

/** A horizontally scrollable, single-select row of Material 3 FilterChips — a shared component
 * (design doc §4.4), not specific to any one screen's filter set. [enabled] and [loading] default
 * to "always enabled, never loading" so existing callers (e.g. Detections) are unaffected; the Map
 * screen's range chips are the first caller to actually use them (a chip mid-request shows a small
 * spinner in place of its label and is disabled while any competing request is active).
 *
 * Every chip state (normal/selected/disabled/disabled-selected) gets an explicit, theme-aware
 * color rather than relying on Material3's own FilterChip defaults, whose disabled colors are too
 * low-contrast against this app's dark theme background to read as anything but invisible. Note
 * Material3's [androidx.compose.material3.SelectableChipColors] has only one `disabledLabelColor`
 * slot — it is shared between disabled-unselected and disabled-selected (there is no separate
 * "disabled selected label" color to set); the two disabled states are told apart by their
 * container and border instead. */
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
    val colors = FilterChipDefaults.filterChipColors(
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
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { option ->
            val isSelected = option == selected
            val isEnabled = enabled(option)
            val isLoading = loading(option)
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
                            Text(label(option))
                        }
                    } else {
                        Text(label(option))
                    }
                },
                colors = colors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = isEnabled,
                    selected = isSelected,
                    borderColor = outline,
                    selectedBorderColor = Color.Transparent,
                    disabledBorderColor = outline.copy(alpha = 0.7f),
                    disabledSelectedBorderColor = primary.copy(alpha = 0.6f)
                )
            )
        }
    }
}
