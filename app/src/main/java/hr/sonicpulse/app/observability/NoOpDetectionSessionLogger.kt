package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selected when `BuildConfig.ENABLE_SESSION_LOGGING` is false — every call is a no-op, no
 * buffer is ever allocated, and [hasCompletedSession] never becomes true. */
class NoOpDetectionSessionLogger : DetectionSessionLogger {

    private val _hasCompletedSession = MutableStateFlow(false)
    override val hasCompletedSession: StateFlow<Boolean> = _hasCompletedSession.asStateFlow()

    override fun startSession(config: EngineConfig) = Unit

    override fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?) = Unit

    override fun finishSession() = Unit

    override fun exportJson(): String? = null
}
