package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveDetectionEngine
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig

/**
 * [DetectionProcessor] wrapping the V2 [AdaptiveDetectionEngine]. Owns and constructs its
 * own engine from [engineConfig], so [sampleRate]/[blockSize] can never drift out of sync
 * with the engine actually doing the processing.
 */
class AdaptiveDetectionProcessor(
    override val engineConfig: AdaptiveEngineConfig = AdaptiveEngineConfig()
) : DetectionProcessor {

    private val engine = AdaptiveDetectionEngine(engineConfig)

    override val sampleRate: Int = engineConfig.sampleRate
    override val blockSize: Int = engineConfig.hopSize

    override fun process(block: ShortArray): DetectionProcessingResult {
        val event = engine.process(block)
        return DetectionProcessingResult(
            dbfs = engine.lastDbfs,
            event = event,
            diagnostics = requireNotNull(engine.lastDiagnostics) {
                "engine.process() must set lastDiagnostics for every valid-size hop."
            }
        )
    }
}
