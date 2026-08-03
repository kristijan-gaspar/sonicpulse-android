package hr.sonicpulse.app.ui.detections

import java.util.UUID

/** One row in the Detections list. Named distinctly from `ui.monitoring.DetectionUiModel` (the
 * Monitoring screen's last-detection card) and from the domain `Detection` — same avoidance of
 * same-concept-different-shape name collisions as `MonitoringPhase` vs. `MonitoringState`. */
data class DetectionHistoryItemUiModel(
    val id: UUID,
    val peakDbfs: Double,
    /** Short local time for the list row, e.g. "14:32:07". */
    val listTimestampText: String,
    /** Localized full local date + the same 24-hour time, for the detail bottom sheet only. */
    val detailTimestampText: String,
    val coordinatesText: String,
    /** True renders "Grupirano"/"Grouped", false renders "Nije grupirano"/"Not grouped" — string
     * resource lookup happens in the Composable layer, not here. No confidence value: that's
     * Map-screen-only. */
    val grouped: Boolean
)
