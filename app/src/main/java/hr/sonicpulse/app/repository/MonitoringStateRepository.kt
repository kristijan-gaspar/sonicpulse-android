package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.engine.BlockMetrics
import kotlinx.coroutines.flow.StateFlow

interface MonitoringStateRepository {
    val state: StateFlow<MonitoringState>

    fun monitoringStarted()
    fun monitoringStopped()
    fun monitoringFailed(error: AudioCaptureError)
    fun publishMetrics(metrics: BlockMetrics)
    fun localDetectionOccurred(detection: SessionDetection)
}
