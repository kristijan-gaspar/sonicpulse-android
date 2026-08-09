package hr.sonicpulse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Pure width comparison behind [ResponsiveFilterRow]'s fit decision, pulled out of the
 * [androidx.compose.ui.layout.SubcomposeLayout] measure block so it's plain-JVM testable on its
 * own. Exact equality counts as fitting; an unbounded parent (`availableWidthPx ==
 * `[Constraints.Infinity]) always fits, since there is then no meaningful limit to compare
 * against — the plain `<=` already gives that for free, no special-casing needed. */
internal fun contentFitsAvailableWidth(naturalWidthPx: Int, availableWidthPx: Int): Boolean =
    naturalWidthPx <= availableWidthPx

/**
 * [FilterChipRow] wrapped in a fit check: measures what the full chip row's content would actually
 * need at its natural (unconstrained) width — same chips, same spacing/content padding, so the
 * measurement already reflects the current labels, font scale, and enabled/loading state — and
 * compares that to the width genuinely available here. Never a hardcoded screen-width breakpoint:
 * a narrow device and a wide one at a larger font scale are judged by the exact same rule.
 *
 * - Fits: renders [FilterChipRow] completely unchanged (same LazyRow, same visual style, still
 *   scrollable as a fallback).
 * - Doesn't fit: renders [CompactFilterSelector] instead — a single chip showing the current
 *   selection that opens the same [options] in a dropdown — rather than a chip row whose last chip
 *   is only partially visible.
 */
@Composable
fun <T> ResponsiveFilterRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    loading: (T) -> Boolean = { false }
) {
    val colors = filterChipColors()

    SubcomposeLayout(modifier = modifier) { constraints ->
        // Measured, never placed: a plain (non-lazy) Row of the exact same chips this row would
        // show, bounded by nothing, so its measured width is the row's true content width — a
        // LazyRow can't report this directly since it only ever measures what fits its viewport.
        val naturalWidth = subcompose("measure") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(modifier = Modifier.width(FilterChipRowHorizontalContentPadding))
                options.forEach { option ->
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
                Spacer(modifier = Modifier.width(FilterChipRowHorizontalContentPadding))
            }
        }.first().measure(Constraints()).width

        val fitsInline = contentFitsAvailableWidth(naturalWidthPx = naturalWidth, availableWidthPx = constraints.maxWidth)

        val placeable = subcompose("content") {
            if (fitsInline) {
                FilterChipRow(
                    options = options,
                    selected = selected,
                    onSelect = onSelect,
                    label = label,
                    enabled = enabled,
                    loading = loading,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                CompactFilterSelector(
                    options = options,
                    selected = selected,
                    onSelect = onSelect,
                    label = label,
                    enabled = enabled,
                    loading = loading
                )
            }
        }.first().measure(constraints)

        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

/** A single chip — styled identically to [FilterChipRow]'s own chips, selected state — that shows
 * [selected]'s label and opens [options] in a dropdown on tap, in place of a chip row that
 * genuinely does not fit. [enabled]/[loading] are honored the same way a real chip row would: the
 * trigger reflects [selected]'s own state, and each dropdown entry reflects its own. */
@Composable
internal fun <T> CompactFilterSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    enabled: (T) -> Boolean,
    loading: (T) -> Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = filterChipColors()
    val isSelectedEnabled = enabled(selected)
    val isSelectedLoading = loading(selected)

    Box(modifier = modifier.padding(horizontal = FilterChipRowHorizontalContentPadding)) {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            enabled = isSelectedEnabled,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectedLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(label(selected))
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = colors,
            border = filterChipBorder(isSelectedEnabled, isSelected = true)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    enabled = enabled(option),
                    trailingIcon = if (loading(option)) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
