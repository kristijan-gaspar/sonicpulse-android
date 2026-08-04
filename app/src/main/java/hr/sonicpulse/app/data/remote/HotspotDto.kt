package hr.sonicpulse.app.data.remote

import kotlinx.serialization.Serializable

/** One item of `GET /api/hotspots` (a bare JSON array, not an object wrapper). UUID and timestamp
 * fields stay `String` at the serialization boundary — [HotspotMapper] parses and validates them
 * into the strongly typed domain model. */
@Serializable
data class HotspotDto(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val confidence: Int,
    val deviceCount: Int,
    val firstReceivedAtUtc: String,
    val lastReceivedAtUtc: String
)
