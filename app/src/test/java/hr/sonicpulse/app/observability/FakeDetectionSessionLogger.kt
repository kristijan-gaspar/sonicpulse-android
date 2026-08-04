package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test double for [DetectionSessionLogger] — records calls so tests that only care about
 * *whether/how* a caller (e.g. [hr.sonicpulse.app.ui.monitoring.MonitoringViewModel]) drives the
 * logger don't need a real [JsonDetectionSessionLogger]. */
class FakeDetectionSessionLogger(
    initialHasCompletedSession: Boolean = false,
    private val exportJsonResult: String? = null
) : DetectionSessionLogger {

    private val _hasCompletedSession = MutableStateFlow(initialHasCompletedSession)
    override val hasCompletedSession: StateFlow<Boolean> = _hasCompletedSession.asStateFlow()

    val startedSessions = mutableListOf<EngineConfig>()
    val blocks = mutableListOf<Pair<BlockMetrics, FinalizedEvent?>>()
    var finishSessionCallCount = 0
        private set

    fun setHasCompletedSession(value: Boolean) {
        _hasCompletedSession.value = value
    }

    override fun startSession(config: EngineConfig) {
        startedSessions += config
    }

    override fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?) {
        blocks += metrics to event
    }

    override fun finishSession() {
        finishSessionCallCount++
    }

    override fun exportJson(): String? = exportJsonResult
}
