package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.service.LocationRefreshFailure
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

interface MonitoringStateRepository {
    val state: StateFlow<MonitoringState>

    fun monitoringStarted()
    fun monitoringStopped()
    fun monitoringFailed(error: AudioCaptureError)
    /** A processing-boundary failure (engine/logger/detection construction) — never a genuine
     * AudioRecorder capture failure, see [hr.sonicpulse.app.data.audio.AudioCaptureError]. */
    fun monitoringProcessingFailed()
    fun monitoringStartupFailed(failure: MonitoringStartupFailure)
    fun publishMetrics(metrics: BlockMetrics)
    fun localDetectionOccurred(detection: SessionDetection)
    fun submissionSucceeded(localEventId: UUID)
    fun submissionFailed(localEventId: UUID, reason: SubmissionFailureReason)
    fun cancelPendingSubmissions()

    /** Nonfatal — unlike [monitoringFailed]/[monitoringStartupFailed], never touches isMonitoring. */
    fun locationRefreshFailed(failure: LocationRefreshFailure)

    /** Clears a previous [locationRefreshFailed] error once a refresh succeeds. Does not touch
     * errorEventId — a cleared error is not itself a new one-shot event to show the user. */
    fun locationRefreshSucceeded()

    /** Continuously refreshed while monitoring is active — independent of any single detection. */
    fun updateLocationStatus(
        snapshot: LocationSnapshot,
        permissionLevel: LocationPermissionLevel,
        servicesEnabled: Boolean
    )

    fun publishAudioLevel(dbfs: Double)
}
