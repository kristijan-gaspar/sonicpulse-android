package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

interface MonitoringStateRepository {
    val state: StateFlow<MonitoringState>

    fun monitoringStarted()
    fun monitoringStopped()
    fun monitoringFailed(error: AudioCaptureError)
    fun monitoringStartupFailed(failure: MonitoringStartupFailure)
    fun publishMetrics(metrics: BlockMetrics)
    fun localDetectionOccurred(detection: SessionDetection)
    fun submissionSucceeded(localEventId: UUID)
    fun submissionFailed(localEventId: UUID, reason: SubmissionFailureReason)
    fun cancelPendingSubmissions()

    /** Continuously refreshed while monitoring is active (§2.8) — independent of any single detection. */
    fun updateLocationStatus(
        snapshot: LocationSnapshot,
        permissionLevel: LocationPermissionLevel,
        servicesEnabled: Boolean
    )
}
