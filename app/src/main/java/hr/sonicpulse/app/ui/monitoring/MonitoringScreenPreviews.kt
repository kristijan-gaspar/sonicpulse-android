package hr.sonicpulse.app.ui.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import hr.sonicpulse.app.ui.theme.SonicPulseTheme

/**
 * Previews of [MonitoringContent] only — the stateless presentation layer. [MonitoringScreen]
 * itself is not previewable this way: it needs a real Activity (permission results,
 * shouldShowRequestPermissionRationale) and a Hilt-injected ViewModel/service, neither of which
 * exist in the preview renderer.
 */
@Preview(name = "Idle", showBackground = true)
@Composable
private fun MonitoringContentIdlePreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(phase = MonitoringPhase.Idle),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Acquiring location", showBackground = true)
@Composable
private fun MonitoringContentAcquiringLocationPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.AcquiringLocation,
                locationDisplayState = LocationDisplayState.Searching,
                microphoneActive = true,
                backgroundActive = true,
                currentDbfs = -34f
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Listening", showBackground = true)
@Composable
private fun MonitoringContentListeningPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.Listening,
                locationDisplayState = LocationDisplayState.Gps,
                microphoneActive = true,
                backgroundActive = true,
                currentDbfs = -18f,
                dbfsHistory = List(100) { (-60f + it * 0.5f).coerceIn(-60f, 0f) }
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Precise location required", showBackground = true)
@Composable
private fun MonitoringContentPreciseLocationRequiredPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.PreciseLocationRequired,
                locationDisplayState = LocationDisplayState.PreciseRequired,
                microphoneActive = true,
                backgroundActive = true
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Last detection — sending", showBackground = true)
@Composable
private fun MonitoringContentSendingPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.Listening,
                locationDisplayState = LocationDisplayState.Gps,
                microphoneActive = true,
                backgroundActive = true,
                lastDetection = DetectionUiModel(
                    peakDbfs = -9.4,
                    timestampText = "14:32:07",
                    coordinatesText = "45.80000, 16.00000",
                    sendResult = SendResult.Sending
                )
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Last detection — sent", showBackground = true)
@Composable
private fun MonitoringContentSentPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.Listening,
                locationDisplayState = LocationDisplayState.Gps,
                microphoneActive = true,
                backgroundActive = true,
                lastDetection = DetectionUiModel(
                    peakDbfs = -9.4,
                    timestampText = "14:32:07",
                    coordinatesText = "45.80000, 16.00000",
                    sendResult = SendResult.Sent
                )
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Last detection — failed (no location)", showBackground = true)
@Composable
private fun MonitoringContentFailedPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.Listening,
                locationDisplayState = LocationDisplayState.Gps,
                microphoneActive = true,
                backgroundActive = true,
                lastDetection = DetectionUiModel(
                    peakDbfs = -9.4,
                    timestampText = "14:32:07",
                    coordinatesText = null,
                    sendResult = SendResult.FailedNoLocation
                )
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}

@Preview(name = "Server configuration warning", showBackground = true)
@Composable
private fun MonitoringContentServerConfigurationErrorPreview() {
    SonicPulseTheme {
        MonitoringContent(
            uiState = MonitoringUiState(
                phase = MonitoringPhase.Listening,
                locationDisplayState = LocationDisplayState.Gps,
                microphoneActive = true,
                backgroundActive = true,
                serverConfigurationError = true,
                lastDetection = DetectionUiModel(
                    peakDbfs = -9.4,
                    timestampText = "14:32:07",
                    coordinatesText = "45.80000, 16.00000",
                    sendResult = SendResult.FailedServerConfig
                )
            ),
            onStart = { }, onStop = { }, onEnableLocation = { }
        )
    }
}
