package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig

/**
 * App-level boundary between the audio capture pipeline (e.g. `MonitoringService`) and a
 * concrete detection engine (V1 or V2). Implementations own one engine instance for the
 * lifetime of one monitoring session — see [DetectionProcessorFactory].
 *
 * [engineConfig] is the actual, immutable V2 configuration the processor's engine was
 * built from — exposed so a caller (e.g. the session diagnostic logger) can capture the
 * real session configuration without constructing or guessing one itself, and without
 * casting this interface down to a concrete implementation.
 */
interface DetectionProcessor {
    val sampleRate: Int
    val blockSize: Int
    val engineConfig: AdaptiveEngineConfig

    fun process(block: ShortArray): DetectionProcessingResult
}
