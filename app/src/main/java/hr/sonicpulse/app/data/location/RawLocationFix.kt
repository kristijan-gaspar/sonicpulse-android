package hr.sonicpulse.app.data.location

/**
 * A plain-data stand-in for android.location.Location, so LocationValidator stays pure and
 * JVM-testable without constructing a real (Android-stub) Location object.
 */
data class RawLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeNanos: Long
) {
    init {
        require(latitude in -90.0..90.0) {
            "latitude must be within -90.0..90.0, was $latitude."
        }
        require(longitude in -180.0..180.0) {
            "longitude must be within -180.0..180.0, was $longitude."
        }
        require(accuracyMeters.isFinite() && accuracyMeters >= 0f) {
            "accuracyMeters must be finite and non-negative, was $accuracyMeters."
        }
        require(elapsedRealtimeNanos >= 0) {
            "elapsedRealtimeNanos must be non-negative, was $elapsedRealtimeNanos."
        }
    }
}
