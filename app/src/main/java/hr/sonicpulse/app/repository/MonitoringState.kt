package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.engine.DetectionState

data class MonitoringState(
    val isMonitoring: Boolean = false,
    val liveDbfs: Double = -120.0,
    val liveBaseline: Double = -120.0,
    val engineState: DetectionState = DetectionState.IDLE,
    val sessionDetections: List<SessionDetection> = emptyList(),
    val captureError: AudioCaptureError? = null
)
