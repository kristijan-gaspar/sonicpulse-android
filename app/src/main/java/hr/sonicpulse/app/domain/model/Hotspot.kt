package hr.sonicpulse.app.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A geographic hotspot as returned by the backend's `GET /api/hotspots` endpoint.
 */
data class Hotspot(
    val id: UUID,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val deviceCount: Int,
    val firstReceivedAtUtc: Instant,
    val lastReceivedAtUtc: Instant
)
