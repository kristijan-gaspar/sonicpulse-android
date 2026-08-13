package hr.sonicpulse.app.observability

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selected when `BuildConfig.ENABLE_SESSION_LOGGING` is false — every call is a no-op, no
 * buffer is ever allocated, and [hasCompletedSession] never becomes true. `@Inject constructor`
 * so `di/ObservabilityModule` can obtain it via a Dagger-generated `Provider`, matching
 * [JsonDetectionSessionLogger]'s own constructor injection. */
@Singleton
class NoOpDetectionSessionLogger @Inject constructor() : DetectionSessionLogger {

    private val _hasCompletedSession = MutableStateFlow(false)
    override val hasCompletedSession: StateFlow<Boolean> = _hasCompletedSession.asStateFlow()

    override fun startSession() = Unit

    override fun onHop(diagnostics: AdaptiveHopDiagnostics, event: DetectionEvent?) = Unit

    override fun finishSession() = Unit

    override fun exportJson(): String? = null
}
