package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveDetectionEngine
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig

/**
 * [DetectionProcessor] wrapping the V2 [AdaptiveDetectionEngine]. Owns and constructs its
 * own engine from [config], so [sampleRate]/[blockSize] can never drift out of sync with
 * the engine actually doing the processing.
 */
class AdaptiveDetectionProcessor(
    private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()
) : DetectionProcessor {

    private val engine = AdaptiveDetectionEngine(config)

    override val sampleRate: Int = config.sampleRate
    override val blockSize: Int = config.hopSize

    override fun process(block: ShortArray): DetectionProcessingResult {
        val event = engine.process(block)
        return DetectionProcessingResult(dbfs = engine.lastDbfs, event = event)
    }
}
