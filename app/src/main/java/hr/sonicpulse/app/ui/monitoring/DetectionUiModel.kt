package hr.sonicpulse.app.ui.monitoring

/**
 * Display-ready shape of the most recent [hr.sonicpulse.app.domain.model.SessionDetection].
 * [latitudeText]/[longitudeText] are pre-formatted numeric strings (fixed precision, dot decimal
 * separator) — the Composable wraps them in the localized "Lat: … · Lon: …" template. Both are
 * null together when the detection's own location snapshot carried no coordinates (e.g.
 * [hr.sonicpulse.app.data.location.LocationSnapshot.NoFixYet]) — the Composable supplies the
 * localized unavailable placeholder for that case.
 */
data class DetectionUiModel(
    val peakDbfs: Double,
    val timestampText: String,
    val latitudeText: String?,
    val longitudeText: String?,
    val sendResult: SendResult
)
