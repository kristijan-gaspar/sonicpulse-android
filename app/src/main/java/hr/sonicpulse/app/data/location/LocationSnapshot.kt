package hr.sonicpulse.app.data.location

sealed interface LocationSnapshot {
    data object NoFixYet : LocationSnapshot
    data object Invalid : LocationSnapshot
    data class Stale(val ageMillis: Long) : LocationSnapshot
    data class Inaccurate(val accuracyMeters: Float) : LocationSnapshot
    data class Valid(val latitude: Double, val longitude: Double, val accuracyMeters: Float) : LocationSnapshot
}
