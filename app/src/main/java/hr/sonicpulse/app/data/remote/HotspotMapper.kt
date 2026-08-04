package hr.sonicpulse.app.data.remote

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID

/**
 * Maps one [HotspotDto] to the domain [Hotspot], validating every backend guarantee (§5 of the
 * plan). A malformed UUID/timestamp or a violated guarantee is a real protocol failure — this
 * deliberately does not catch [IllegalArgumentException]/[java.time.format.DateTimeParseException]
 * and does not clamp or coerce out-of-range values; a bad hotspot must never silently become an
 * empty or partial successful response.
 */
object HotspotMapper {
    fun toDomain(dto: HotspotDto): Hotspot {
        val latitude = dto.latitude
        val longitude = dto.longitude
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        require(dto.radiusMeters.isFinite() && dto.radiusMeters >= 0.0) { "Invalid radiusMeters: ${dto.radiusMeters}" }
        require(dto.confidence in 0..100) { "Invalid confidence: ${dto.confidence}" }
        require(dto.deviceCount >= 2) { "Invalid deviceCount: ${dto.deviceCount}" }

        val firstReceivedAtUtc = Instant.parse(dto.firstReceivedAtUtc)
        val lastReceivedAtUtc = Instant.parse(dto.lastReceivedAtUtc)
        require(!firstReceivedAtUtc.isAfter(lastReceivedAtUtc)) {
            "firstReceivedAtUtc ($firstReceivedAtUtc) is after lastReceivedAtUtc ($lastReceivedAtUtc)"
        }

        return Hotspot(
            id = UUID.fromString(dto.id),
            latitude = latitude,
            longitude = longitude,
            radiusMeters = dto.radiusMeters,
            confidence = dto.confidence,
            deviceCount = dto.deviceCount,
            firstReceivedAtUtc = firstReceivedAtUtc,
            lastReceivedAtUtc = lastReceivedAtUtc
        )
    }
}
