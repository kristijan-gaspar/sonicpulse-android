package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.engine.BlockMetrics
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
}
