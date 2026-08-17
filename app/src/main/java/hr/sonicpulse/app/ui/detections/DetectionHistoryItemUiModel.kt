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
    /** Localized full local date + the same 24-hour time, for the detail bottom sheet's "Detected"
     * row — the on-device peak instant (`peakTimeClient`) when the backend has one, falling back to
     * `receivedAtUtc` otherwise. See [hr.sonicpulse.app.domain.model.eventTime]. */
    val detailTimestampText: String,
    /** Localized full local date + the same 24-hour time as [detailTimestampText], but always from
     * `receivedAtUtc` — the detail bottom sheet's "Received by server" row. Distinct from
     * [detailTimestampText] whenever `peakTimeClient` is present; equal to it when absent (both
     * fall back to the same instant). */
    val receivedAtServerText: String,
    /** Pre-formatted numeric strings (fixed precision, dot decimal separator) — the Composable
     * wraps them in the localized "Lat: … · Lon: …" template for the list row, and in separate
     * labeled rows for the detail bottom sheet. */
    val latitudeText: String,
    val longitudeText: String,
    /** Pre-formatted, rounded to whole meters — the Composable wraps it in the localized "±X m"
     * template for the detail bottom sheet only. */
    val gpsAccuracyText: String,
    /** True renders "Grupirano"/"Grouped", false renders "Nije grupirano"/"Not grouped" — string
     * resource lookup happens in the Composable layer, not here. */
    val grouped: Boolean
)
