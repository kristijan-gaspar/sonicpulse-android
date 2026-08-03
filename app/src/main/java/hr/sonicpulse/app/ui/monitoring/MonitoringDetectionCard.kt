package hr.sonicpulse.app.ui.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.sonicpulse.app.R
import hr.sonicpulse.app.ui.components.SectionHeader
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing

/** Last detection + send status card (design spec §5.1G). */
@Composable
fun LastDetectionCard(lastDetection: DetectionUiModel?, modifier: Modifier = Modifier) {
    val accentColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(color = accentColor, size = Size(3.dp.toPx(), size.height)) },
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(stringResource(R.string.section_last_detection))
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (lastDetection == null) {
                Text(
                    text = stringResource(R.string.no_detections_yet),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                DetectionRow(lastDetection)
                Spacer(modifier = Modifier.height(Spacing.sm))
                SendResultBanner(lastDetection.sendResult)
            }
        }
    }
}

@Composable
private fun DetectionRow(detection: DetectionUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), AppShapes.IconContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "%.1f".format(detection.peakDbfs),
                    style = MonospaceValueStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = detection.timestampText,
                    style = MonospaceValueStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            Text(
                text = detection.coordinatesText ?: stringResource(R.string.coordinates_unavailable),
                style = MonospaceValueStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun SendResultBanner(sendResult: SendResult, modifier: Modifier = Modifier) {
    val background: Color
    val contentColor: Color
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val textRes: Int
    when (sendResult) {
        SendResult.Sent -> {
            background = SemanticColors.SuccessBg
            contentColor = SemanticColors.Success
            icon = Icons.Filled.CloudDone
            textRes = R.string.send_success
        }
        SendResult.FailedNoLocation -> {
            background = SemanticColors.DangerBg
            contentColor = SemanticColors.Danger
            icon = Icons.Filled.CloudOff
            textRes = R.string.send_failed_no_location
        }
        SendResult.FailedNetwork -> {
            background = SemanticColors.DangerBg
            contentColor = SemanticColors.Danger
            icon = Icons.Filled.CloudOff
            textRes = R.string.send_failed_network
        }
        SendResult.FailedServerConfig -> {
            background = SemanticColors.DangerBg
            contentColor = SemanticColors.Danger
            icon = Icons.Filled.CloudOff
            textRes = R.string.send_failed_server_config
        }
        SendResult.FailedOther -> {
            background = SemanticColors.DangerBg
            contentColor = SemanticColors.Danger
            icon = Icons.Filled.CloudOff
            textRes = R.string.send_failed_other
        }
        SendResult.Sending -> {
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            contentColor = MaterialTheme.colorScheme.primary
            icon = Icons.Filled.CloudUpload
            textRes = R.string.send_in_progress
        }
    }

    Surface(modifier = modifier.fillMaxWidth(), shape = AppShapes.ChipOrBadge, color = background) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(text = stringResource(textRes), style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}
