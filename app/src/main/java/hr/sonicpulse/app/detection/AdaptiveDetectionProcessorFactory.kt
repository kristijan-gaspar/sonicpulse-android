package hr.sonicpulse.app.detection

import hr.sonicpulse.app.observability.DetectionSessionLogger
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig

/**
 * [DetectionProcessorFactory] for the V2 adaptive engine. [config] is immutable/stateless
 * configuration and may safely be shared across sessions; each [create] call still builds
 * a brand-new [AdaptiveDetectionProcessor] (and therefore a brand-new adaptive engine), so
 * no session ever inherits another session's background/state-machine state.
 *
 * Every processor [create] returns is wrapped in [LoggingAdaptiveDetectionProcessor], which
 * reports each hop's diagnostics to [detectionSessionLogger] — the shared, DI-selected
 * [DetectionSessionLogger] singleton (`JsonDetectionSessionLogger` or `NoOpDetectionSessionLogger`,
 * chosen once in `di/ObservabilityModule`; this factory has no opinion on which).
 */
class AdaptiveDetectionProcessorFactory(
    private val config: AdaptiveEngineConfig = AdaptiveEngineConfig(),
    private val detectionSessionLogger: DetectionSessionLogger
) : DetectionProcessorFactory {

    override fun create(): DetectionProcessor {
        val processor = AdaptiveDetectionProcessor(config)
        return LoggingAdaptiveDetectionProcessor(
            delegate = processor,
            logger = detectionSessionLogger
        )
    }
}
