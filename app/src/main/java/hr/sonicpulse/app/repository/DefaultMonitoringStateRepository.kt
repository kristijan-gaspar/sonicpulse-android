package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
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
        // ~13 Hz: within the plan's 10-15 UI updates/second budget for high-frequency block metrics.
        const val METRICS_THROTTLE_INTERVAL_MILLIS = 75L
        const val MAX_SESSION_DETECTIONS = 100
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
        _state.update { it.copy(isMonitoring = false, captureError = error, startupError = null) }
    }

    override fun monitoringStartupFailed(failure: MonitoringStartupFailure) {
        _state.update { it.copy(isMonitoring = false, startupError = failure, captureError = null) }
    }

    override fun publishMetrics(metrics: BlockMetrics) {
        if (!throttle.shouldEmit()) {
            return
        }
        _state.update {
            it.copy(
                liveDbfs = metrics.dbfs,
                liveBaseline = metrics.baseline,
                engineState = metrics.state
            )
        }
    }

    override fun localDetectionOccurred(detection: SessionDetection) {
        _state.update {
            it.copy(sessionDetections = (it.sessionDetections + detection).takeLast(MAX_SESSION_DETECTIONS))
        }
    }

    override fun submissionSucceeded(localEventId: UUID) {
        _state.update { state ->
            state.copy(
                sessionDetections = updateStatus(state.sessionDetections, localEventId, SubmissionStatus.Sent),
                submissionCounters = state.submissionCounters.copy(
                    submissionSucceeded = state.submissionCounters.submissionSucceeded + 1
                )
            )
        }
    }

    override fun submissionFailed(localEventId: UUID, reason: SubmissionFailureReason) {
        _state.update { state ->
            state.copy(
                sessionDetections = updateStatus(state.sessionDetections, localEventId, SubmissionStatus.Failed(reason)),
                submissionCounters = incrementCounter(state.submissionCounters, reason),
                serverConfigurationError = state.serverConfigurationError ||
                    reason == SubmissionFailureReason.UNAUTHORIZED
            )
        }
    }

    private fun updateStatus(
        detections: List<SessionDetection>,
        localEventId: UUID,
        status: SubmissionStatus
    ): List<SessionDetection> = detections.map {
        if (it.localEventId == localEventId) it.copy(submissionStatus = status) else it
    }

    private fun incrementCounter(counters: SubmissionCounters, reason: SubmissionFailureReason): SubmissionCounters =
        when (reason) {
            SubmissionFailureReason.NO_LOCATION -> counters.copy(droppedNoLocation = counters.droppedNoLocation + 1)
            SubmissionFailureReason.STALE_LOCATION -> counters.copy(droppedStaleLocation = counters.droppedStaleLocation + 1)
            SubmissionFailureReason.INACCURATE_LOCATION ->
                counters.copy(droppedInaccurateLocation = counters.droppedInaccurateLocation + 1)
            SubmissionFailureReason.NETWORK_ERROR -> counters.copy(droppedNetwork = counters.droppedNetwork + 1)
            SubmissionFailureReason.BAD_REQUEST ->
                counters.copy(submissionFailedBadRequest = counters.submissionFailedBadRequest + 1)
            SubmissionFailureReason.UNAUTHORIZED ->
                counters.copy(submissionFailedUnauthorized = counters.submissionFailedUnauthorized + 1)
            SubmissionFailureReason.RATE_LIMITED -> counters.copy(submissionRateLimited = counters.submissionRateLimited + 1)
            SubmissionFailureReason.CLIENT_ERROR -> counters.copy(submissionFailedClient = counters.submissionFailedClient + 1)
            SubmissionFailureReason.SERVER_ERROR -> counters.copy(submissionFailedServer = counters.submissionFailedServer + 1)
        }
}
