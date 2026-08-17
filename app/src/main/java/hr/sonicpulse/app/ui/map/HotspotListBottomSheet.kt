package hr.sonicpulse.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.sonicpulse.app.R
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Secondary navigation for the Map screen (plan items 5-7) — lists exactly the [hotspots] the
 * caller already has loaded for the currently committed time-range filter; this composable never
 * triggers its own reload. Selecting a row hands the hotspot back via [onSelectHotspot], which
 * [MapContent] wires to the exact same `focusHotspot` a marker/polygon tap uses — never a second,
 * parallel hotspot-detail path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HotspotListBottomSheet(
    hotspots: List<Hotspot>,
    onDismiss: () -> Unit,
    onSelectHotspot: (Hotspot) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = AppShapes.BottomSheet, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.hotspot_list_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            LazyColumn(
                contentPadding = PaddingValues(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(hotspots, key = { it.id }) { hotspot ->
                    HotspotListRow(hotspot = hotspot, onClick = { onSelectHotspot(hotspot) })
                }
            }
        }
    }
}

@Composable
private fun HotspotListRow(hotspot: Hotspot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bucketColor = bucketFor(hotspot.deviceCount).color
    val deviceCountLabel = HotspotGeoJson.deviceCountLabel(hotspot.deviceCount)
    val radiusText = stringResource(R.string.unit_meters_short, hotspot.radiusMeters.roundToInt())

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(28.dp).background(bucketColor, CircleShape), contentAlignment = Alignment.Center) {
                Text(text = deviceCountLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = radiusText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
