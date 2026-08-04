package hr.sonicpulse.app.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A geographic hotspot as returned by the backend's `GET /api/hotspots` endpoint. Confidence is
 * computed server-side and displayed unchanged — no confidence formula exists on the client.
 */
data class Hotspot(
    val id: UUID,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val confidence: Int,
    val deviceCount: Int,
    val firstReceivedAtUtc: Instant,
    val lastReceivedAtUtc: Instant
)
