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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeMonitoringStateRepository : MonitoringStateRepository {

    private companion object {
        const val MAX_DBFS_HISTORY = 100
    }

    private val _state = MutableStateFlow(MonitoringState())
    override val state: StateFlow<MonitoringState> = _state.asStateFlow()

    val publishedMetrics = mutableListOf<BlockMetrics>()
    val occurredDetections = mutableListOf<SessionDetection>()

    override fun monitoringStarted() {
        _state.value = MonitoringState(isMonitoring = true)
    }

    override fun monitoringStopped() {
        _state.update { it.copy(isMonitoring = false) }
    }

    override fun monitoringFailed(error: AudioCaptureError) {
        _state.update {
            it.copy(isMonitoring = false, captureError = error, startupError = null, errorEventId = it.errorEventId + 1)
        }
    }

    override fun monitoringStartupFailed(failure: MonitoringStartupFailure) {
        _state.update {
            it.copy(isMonitoring = false, startupError = failure, captureError = null, errorEventId = it.errorEventId + 1)
        }
    }

    override fun publishMetrics(metrics: BlockMetrics) {
        publishedMetrics += metrics
        _state.update {
            it.copy(
                liveDbfs = metrics.dbfs,
                liveBaseline = metrics.baseline,
                dbfsHistory = (it.dbfsHistory + metrics.dbfs).takeLast(MAX_DBFS_HISTORY),
                engineState = metrics.state
            )
        }
    }

    override fun localDetectionOccurred(detection: SessionDetection) {
        occurredDetections += detection
        _state.update { it.copy(sessionDetections = SessionDetectionRetention.append(it.sessionDetections, detection)) }
    }

    override fun submissionSucceeded(localEventId: UUID) {
        _state.update { SubmissionTransitions.applySuccess(it, localEventId) }
    }

    override fun submissionFailed(localEventId: UUID, reason: SubmissionFailureReason) {
        _state.update { SubmissionTransitions.applyFailure(it, localEventId, reason) }
    }

    override fun cancelPendingSubmissions() {
        _state.update { SubmissionTransitions.cancelPending(it) }
    }

    val locationRefreshFailures = mutableListOf<LocationRefreshFailure>()

    override fun locationRefreshFailed(failure: LocationRefreshFailure) {
        locationRefreshFailures += failure
        _state.update { it.copy(locationRefreshError = failure, errorEventId = it.errorEventId + 1) }
    }

    override fun locationRefreshSucceeded() {
        _state.update { it.copy(locationRefreshError = null) }
    }

    override fun updateLocationStatus(
        snapshot: LocationSnapshot,
        permissionLevel: LocationPermissionLevel,
        servicesEnabled: Boolean
    ) {
        _state.update {
            it.copy(
                currentLocationSnapshot = snapshot,
                locationPermissionLevel = permissionLevel,
                locationServicesEnabled = servicesEnabled
            )
        }
    }

    fun setState(state: MonitoringState) {
        _state.value = state
    }
}
