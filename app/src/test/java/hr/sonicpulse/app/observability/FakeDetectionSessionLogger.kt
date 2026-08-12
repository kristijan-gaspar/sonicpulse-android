package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test double for [DetectionSessionLogger] — records calls so tests that only care about
 * *whether/how* a caller (e.g. [hr.sonicpulse.app.ui.monitoring.MonitoringViewModel]) drives the
 * logger don't need a real [JsonDetectionSessionLogger]. Mirrors the real contract closely enough
 * for [hasCompletedSession] to be trustworthy in tests: [startSession] sets it false,
 * [finishSession] sets it true — but only when a session was actually started, so a stray
 * [finishSession] call can't fabricate a completed session out of nothing. */
class FakeDetectionSessionLogger(
    initialHasCompletedSession: Boolean = false,
    private val exportJsonResult: String? = null
) : DetectionSessionLogger {

    private val _hasCompletedSession = MutableStateFlow(initialHasCompletedSession)
    override val hasCompletedSession: StateFlow<Boolean> = _hasCompletedSession.asStateFlow()

    val startedSessions = mutableListOf<EngineConfig>()
    val blocks = mutableListOf<Pair<BlockMetrics, FinalizedCandidate?>>()
    var finishSessionCallCount = 0
        private set

    private var sessionStarted = false

    fun setHasCompletedSession(value: Boolean) {
        _hasCompletedSession.value = value
    }

    override fun startSession(config: EngineConfig) {
        startedSessions += config
        sessionStarted = true
        _hasCompletedSession.value = false
    }

    override fun onBlock(metrics: BlockMetrics, finalizedCandidate: FinalizedCandidate?) {
        blocks += metrics to finalizedCandidate
    }

    override fun finishSession() {
        finishSessionCallCount++
        if (sessionStarted) {
            _hasCompletedSession.value = true
            sessionStarted = false
        }
    }

    override fun exportJson(): String? = exportJsonResult
}
