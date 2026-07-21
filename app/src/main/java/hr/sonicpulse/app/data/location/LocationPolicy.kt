package hr.sonicpulse.app.data.location

import com.google.android.gms.location.Priority

data class LocationPolicy(
    val maxLocationAgeMillis: Long = 10_000,
    val maxLocationAccuracyMeters: Float = 50.0f,
    val updateIntervalMillis: Long = 5_000,
    val priority: Int = Priority.PRIORITY_HIGH_ACCURACY
) {
    init {
        require(maxLocationAgeMillis > 0) {
            "maxLocationAgeMillis must be positive, was $maxLocationAgeMillis."
        }
        require(maxLocationAccuracyMeters.isFinite() && maxLocationAccuracyMeters > 0f) {
            "maxLocationAccuracyMeters must be finite and positive, was $maxLocationAccuracyMeters."
        }
        require(updateIntervalMillis > 0) {
            "updateIntervalMillis must be positive, was $updateIntervalMillis."
        }
    }
}
