package hr.sonicpulse.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.sonicpulse.app.R
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.SemanticColors

/** Grouped/Ungrouped status, derived purely from whether a detection has a `hotspotId` (plan
 * §2.10, closed decision) — Detections-screen only, never shown on the Monitoring screen. */
@Composable
fun StatusBadge(grouped: Boolean, modifier: Modifier = Modifier) {
    val textRes = if (grouped) R.string.status_grouped else R.string.status_ungrouped
    val color = if (grouped) SemanticColors.Success else SemanticColors.Warning
    val background = if (grouped) SemanticColors.SuccessBg else SemanticColors.WarningBg
    val icon = if (grouped) Icons.Filled.CheckCircle else Icons.Filled.Schedule

    Surface(modifier = modifier, shape = AppShapes.ChipOrBadge, color = background) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(
                text = stringResource(textRes),
                modifier = Modifier.padding(start = 4.dp),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}
