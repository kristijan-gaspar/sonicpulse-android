package hr.sonicpulse.app.ui.monitoring

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.sonicpulse.app.R
import hr.sonicpulse.app.ui.components.AppCard
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing

/** Status pill (design spec §5.1B) — reflects [MonitoringPhase]. */
@Composable
fun StatusPill(phase: MonitoringPhase, modifier: Modifier = Modifier) {
    val (background, contentColor, textRes, blinking) = when (phase) {
        MonitoringPhase.Idle ->
            PillStyle(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, R.string.state_idle, false)
        MonitoringPhase.AcquiringLocation ->
            PillStyle(SemanticColors.WarningBg, SemanticColors.Warning, R.string.state_acquiring_location, true)
        MonitoringPhase.Listening ->
            PillStyle(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.primary, R.string.state_listening, true)
        MonitoringPhase.PreciseLocationRequired ->
            PillStyle(SemanticColors.DangerBg, SemanticColors.Danger, R.string.state_precise_location_required, false)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(shape = AppShapes.Pill, color = background) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                StatusDot(color = contentColor, blinking = blinking)
                Text(
                    text = stringResource(textRes),
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private data class PillStyle(val background: Color, val contentColor: Color, val textRes: Int, val blinking: Boolean)

@Composable
private fun StatusDot(color: Color, blinking: Boolean) {
    // No infiniteRepeatable at all for the static case — a non-blinking dot must not keep an
    // animation (and its recompositions) running forever in the background.
    val alpha = if (blinking) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        val animatedAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(animation = tween(1400), repeatMode = RepeatMode.Reverse),
            label = "statusDotAlpha"
        )
        animatedAlpha
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/** Hint text (design spec §5.1C). */
@Composable
fun MonitoringHintText(phase: MonitoringPhase, modifier: Modifier = Modifier) {
    val textRes = when (phase) {
        MonitoringPhase.Idle -> R.string.hint_idle
        MonitoringPhase.AcquiringLocation -> R.string.hint_acquiring_location
        MonitoringPhase.Listening -> R.string.hint_listening
        MonitoringPhase.PreciseLocationRequired -> R.string.hint_precise_location_required
    }
    Text(
        text = stringResource(textRes),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/** Start/Stop button (design spec §5.1D). */
@Composable
fun MonitoringActionButton(
    phase: MonitoringPhase,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val singleButtonModifier = Modifier.fillMaxWidth().height(54.dp)
    when (phase) {
        MonitoringPhase.Idle -> Button(
            onClick = onStart,
            modifier = modifier.then(singleButtonModifier),
            shape = AppShapes.Button
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.action_start), modifier = Modifier.padding(start = Spacing.sm))
        }

        MonitoringPhase.AcquiringLocation, MonitoringPhase.Listening -> OutlinedButton(
            onClick = onStop,
            modifier = modifier.then(singleButtonModifier),
            shape = AppShapes.Button
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text(stringResource(R.string.action_stop), modifier = Modifier.padding(start = Spacing.sm))
        }

        // Monitoring is still active here (§2.8) — the enable-location action must not be the
        // only control on screen, or there would be no way to stop monitoring from this state.
        MonitoringPhase.PreciseLocationRequired -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Button(
                onClick = onEnableLocation,
                modifier = singleButtonModifier,
                shape = AppShapes.Button,
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.Danger, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Text(stringResource(R.string.action_enable_location), modifier = Modifier.padding(start = Spacing.sm))
            }
            OutlinedButton(
                onClick = onStop,
                modifier = singleButtonModifier,
                shape = AppShapes.Button
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text(stringResource(R.string.action_stop), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

/** Location status row (design spec §5.1F) — Mikrofon / Lokacija / Pozadina mini-cards. */
@Composable
fun LocationStatusRow(
    microphoneActive: Boolean,
    locationDisplayState: LocationDisplayState,
    backgroundActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MiniStatusCard(
            icon = Icons.Filled.Mic,
            label = stringResource(R.string.label_microphone),
            statusText = stringResource(if (microphoneActive) R.string.location_status_active else R.string.location_status_unavailable),
            dotColor = if (microphoneActive) SemanticColors.Success else SemanticColors.Warning,
            blinking = false,
            modifier = Modifier.weight(1f)
        )
        val (locationStatusRes, locationDotColor, locationBlinking) = when (locationDisplayState) {
            LocationDisplayState.Gps -> Triple(R.string.location_status_gps, SemanticColors.Success, false)
            LocationDisplayState.Searching -> Triple(R.string.location_status_searching, SemanticColors.Warning, true)
            LocationDisplayState.PreciseRequired -> Triple(R.string.location_status_precise_required, SemanticColors.Danger, false)
            LocationDisplayState.Unavailable -> Triple(R.string.location_status_unavailable, SemanticColors.Warning, false)
        }
        MiniStatusCard(
            icon = Icons.Filled.LocationOn,
            label = stringResource(R.string.label_location),
            statusText = stringResource(locationStatusRes),
            dotColor = locationDotColor,
            blinking = locationBlinking,
            modifier = Modifier.weight(1f)
        )
        MiniStatusCard(
            icon = Icons.Filled.CloudSync,
            label = stringResource(R.string.label_background),
            statusText = stringResource(if (backgroundActive) R.string.location_status_active else R.string.location_status_inactive),
            dotColor = if (backgroundActive) SemanticColors.Success else SemanticColors.Warning,
            blinking = false,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MiniStatusCard(
    icon: ImageVector,
    label: String,
    statusText: String,
    dotColor: Color,
    blinking: Boolean,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(top = Spacing.xs)
            ) {
                StatusDot(color = dotColor, blinking = blinking)
                Text(text = statusText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Persistent server-configuration warning (plan §2.9's `serverConfigurationError`). Shown for the
 * rest of the monitoring session after a 401/403 — every subsequent submission will keep failing
 * until the API key/backend configuration is fixed, so this must stay visible independent of
 * whatever the last individual detection's send-result banner currently shows.
 */
@Composable
fun ServerConfigurationWarning(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = AppShapes.ChipOrBadge, color = SemanticColors.DangerBg) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = SemanticColors.Danger, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.error_server_configuration),
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.Danger
            )
        }
    }
}
