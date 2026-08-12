package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig

/**
 * [DetectionProcessorFactory] for the V2 adaptive engine. [config] is immutable/stateless
 * configuration and may safely be shared across sessions; each [create] call still builds
 * a brand-new [AdaptiveDetectionProcessor] (and therefore a brand-new adaptive engine), so
 * no session ever inherits another session's background/state-machine state.
 */
class AdaptiveDetectionProcessorFactory(
    private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()
) : DetectionProcessorFactory {

    override fun create(): DetectionProcessor = AdaptiveDetectionProcessor(config)
}
