package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.BlockMetrics
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeMonitoringStateRepository : MonitoringStateRepository {

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
        _state.update { it.copy(isMonitoring = false, captureError = error, startupError = null) }
    }

    override fun monitoringStartupFailed(failure: MonitoringStartupFailure) {
        _state.update { it.copy(isMonitoring = false, startupError = failure, captureError = null) }
    }

    override fun publishMetrics(metrics: BlockMetrics) {
        publishedMetrics += metrics
        _state.update {
            it.copy(
                liveDbfs = metrics.dbfs,
                liveBaseline = metrics.baseline,
                engineState = metrics.state
            )
        }
    }

    override fun localDetectionOccurred(detection: SessionDetection) {
        occurredDetections += detection
        _state.update { it.copy(sessionDetections = it.sessionDetections + detection) }
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

    fun setState(state: MonitoringState) {
        _state.value = state
    }
}
