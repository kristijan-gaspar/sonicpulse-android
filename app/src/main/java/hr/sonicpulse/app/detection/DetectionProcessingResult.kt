package hr.sonicpulse.app.detection

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics

/** One block/hop's outcome from a [DetectionProcessor]: its dBFS level, an accepted
 * [DetectionEvent] if one closed on this call (`null` on every other call), and the
 * corresponding [diagnostics] snapshot for that exact hop — see [AdaptiveHopDiagnostics]. */
data class DetectionProcessingResult(
    val dbfs: Double,
    val event: DetectionEvent?,
    val diagnostics: AdaptiveHopDiagnostics
)
