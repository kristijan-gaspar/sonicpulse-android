package hr.sonicpulse.app.data.location

sealed interface LocationSnapshot {
    data object NoFixYet : LocationSnapshot
    data object Invalid : LocationSnapshot
    data class Stale(val ageMillis: Long) : LocationSnapshot
    data class Inaccurate(val accuracyMeters: Float) : LocationSnapshot

    /** Guarantees its data is submittable to the backend, which requires finite latitude/longitude
     * within range and a strictly positive GPS accuracy — [hr.sonicpulse.app.domain.model.SessionDetection]
     * relies on this to never carry a location the backend would reject. */
    data class Valid(val latitude: Double, val longitude: Double, val accuracyMeters: Float) : LocationSnapshot {
        init {
            require(latitude.isFinite() && latitude in -90.0..90.0) {
                "latitude must be finite and within -90.0..90.0, was $latitude."
            }
            require(longitude.isFinite() && longitude in -180.0..180.0) {
                "longitude must be finite and within -180.0..180.0, was $longitude."
            }
            require(accuracyMeters.isFinite() && accuracyMeters > 0f) {
                "accuracyMeters must be finite and strictly positive, was $accuracyMeters."
            }
        }
    }
}
