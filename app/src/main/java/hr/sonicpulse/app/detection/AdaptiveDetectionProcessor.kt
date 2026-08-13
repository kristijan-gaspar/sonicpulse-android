package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveDetectionEngine
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics

/**
 * [DetectionProcessor] wrapping the V2 [AdaptiveDetectionEngine]. Owns and constructs its
 * own engine from [config], so [sampleRate]/[blockSize] can never drift out of sync with
 * the engine actually doing the processing.
 *
 * Adapts the engine to the generic [DetectionProcessor] boundary only — it does not know
 * about session logging. [lastDiagnostics] exposes the engine's own diagnostics for the
 * V2-specific [LoggingAdaptiveDetectionProcessor] decorator; it is `internal`, not part of
 * [DetectionProcessor]/[DetectionProcessingResult], so the generic boundary stays engine-agnostic.
 */
class AdaptiveDetectionProcessor(
    private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()
) : DetectionProcessor {

    private val engine = AdaptiveDetectionEngine(config)

    override val sampleRate: Int = config.sampleRate
    override val blockSize: Int = config.hopSize

    /** Diagnostics for whichever hop [process] most recently completed — see
     * [AdaptiveDetectionEngine.lastDiagnostics]. `null` before the first hop is ever processed. */
    internal val lastDiagnostics: AdaptiveHopDiagnostics?
        get() = engine.lastDiagnostics

    override fun process(block: ShortArray): DetectionProcessingResult {
        val event = engine.process(block)
        return DetectionProcessingResult(dbfs = engine.lastDbfs, event = event)
    }
}
