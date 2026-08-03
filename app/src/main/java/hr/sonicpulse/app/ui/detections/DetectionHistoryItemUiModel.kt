package hr.sonicpulse.app.ui.detections

import java.util.UUID

/** One row in the Detections list. Named distinctly from `ui.monitoring.DetectionUiModel` (the
 * Monitoring screen's last-detection card) and from the domain `Detection` — same avoidance of
 * same-concept-different-shape name collisions as `MonitoringPhase` vs. `MonitoringState`. */
data class DetectionHistoryItemUiModel(
    val id: UUID,
    val peakDbfs: Double,
    val timestampText: String,
    val coordinatesText: String,
    /** True renders "Grupirano"/"Grouped", false renders "Nije grupirano"/"Not grouped" — string
     * resource lookup happens in the Composable layer, not here. No confidence value: that's
     * Map-screen-only. */
    val grouped: Boolean
)
