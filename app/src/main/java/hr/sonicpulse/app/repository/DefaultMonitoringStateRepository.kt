package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class DefaultMonitoringStateRepository @Inject constructor() : MonitoringStateRepository {

    private companion object {
        // 10 Hz: matches the Monitoring screen's live dBFS chart, giving ~10s of history at 100 samples.
        const val METRICS_THROTTLE_INTERVAL_MILLIS = 100L
        const val MAX_DBFS_HISTORY = 100
    }

    private val throttle = MetricsThrottle(METRICS_THROTTLE_INTERVAL_MILLIS)

    private val _state = MutableStateFlow(MonitoringState())
    override val state: StateFlow<MonitoringState> = _state.asStateFlow()

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
        if (!throttle.shouldEmit()) {
            return
        }
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
}
