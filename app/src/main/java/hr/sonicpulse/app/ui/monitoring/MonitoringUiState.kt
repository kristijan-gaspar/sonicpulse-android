package hr.sonicpulse.app.ui.monitoring

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.DetectionState

/**
 * Plain data mapped from MonitoringState — no colors, formatted strings, or other visual
 * decisions. Those are added when the Monitoring screen itself is built (design-system pass).
 */
data class MonitoringUiState(
    val isMonitoring: Boolean = false,
    val liveDbfs: Double = -120.0,
    val liveBaseline: Double = -120.0,
    val engineState: DetectionState = DetectionState.IDLE,
    val sessionDetections: List<SessionDetection> = emptyList(),
    val captureError: AudioCaptureError? = null,
    val startupError: MonitoringStartupFailure? = null
)
