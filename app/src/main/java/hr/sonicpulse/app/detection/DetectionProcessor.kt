package hr.sonicpulse.app.detection

/**
 * App-level boundary between the audio capture pipeline (e.g. `MonitoringService`) and a
 * concrete detection engine (V1 or V2). Implementations own one engine instance for the
 * lifetime of one monitoring session — see [DetectionProcessorFactory].
 */
interface DetectionProcessor {
    val sampleRate: Int
    val blockSize: Int

    fun process(block: ShortArray): DetectionProcessingResult
}
