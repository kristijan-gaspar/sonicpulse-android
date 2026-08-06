package hr.sonicpulse.app.ui.detections

import java.time.LocalDate

/** One sticky-header section of the Detections list — [date] is the device's local calendar date
 * derived from [hr.sonicpulse.app.domain.model.eventTime], left as a raw value here; formatting
 * it as "Today" vs. a localized date string is the Composable's job. */
data class DetectionDateSection(
    val date: LocalDate,
    val items: List<DetectionHistoryItemUiModel>
)
