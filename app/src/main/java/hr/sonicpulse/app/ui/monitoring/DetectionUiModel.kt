package hr.sonicpulse.app.ui.monitoring

/**
 * Display-ready shape of the most recent [hr.sonicpulse.app.domain.model.SessionDetection].
 * [coordinatesText] is null when the detection's own location snapshot carried no coordinates
 * (e.g. [hr.sonicpulse.app.data.location.LocationSnapshot.NoFixYet]) — the Composable supplies
 * the localized placeholder for that case.
 */
data class DetectionUiModel(
    val peakDbfs: Double,
    val timestampText: String,
    val coordinatesText: String?,
    val sendResult: SendResult
)
