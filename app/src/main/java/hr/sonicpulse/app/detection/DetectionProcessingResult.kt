package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.DetectionEvent

/** One block/hop's outcome from a [DetectionProcessor]: its dBFS level, and an accepted
 * [DetectionEvent] if one closed on this call, or `null` on every other call. */
data class DetectionProcessingResult(
    val dbfs: Double,
    val event: DetectionEvent?
)
