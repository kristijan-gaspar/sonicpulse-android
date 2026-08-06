package hr.sonicpulse.app.data.remote

import hr.sonicpulse.app.domain.model.Detection
import java.time.Instant
import java.util.UUID

/**
 * Maps one [DetectionHistoryItemDto] to the domain [Detection], validating every backend
 * guarantee — mirrors [HotspotMapper]'s same approach for the same reason. Deliberately does not
 * catch [IllegalArgumentException] (malformed UUID, or one of this mapper's own `require()`
 * failures) or [java.time.format.DateTimeParseException] (malformed timestamp), and does not clamp
 * or coerce an out-of-range value — a malformed or invalid field is malformed backend data, a real
 * protocol failure, not something to silently drop, null out, or invent a default for.
 */
object DetectionHistoryMapper {
    fun toDomain(dto: DetectionHistoryItemDto): Detection {
        require(dto.sequenceNumber >= 0) { "Invalid sequenceNumber: ${dto.sequenceNumber}" }

        val latitude = dto.latitude
        val longitude = dto.longitude
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        // Strictly positive, finite — the same GPS-accuracy contract LocationSnapshot.Valid
        // already enforces client-side before a detection is ever submitted.
        require(dto.gpsAccuracy.isFinite() && dto.gpsAccuracy > 0.0) { "Invalid gpsAccuracy: ${dto.gpsAccuracy}" }
        // dBFS is a ratio to full scale and can never be positive by definition.
        require(dto.peakDbfs.isFinite() && dto.peakDbfs <= 0.0) { "Invalid peakDbfs: ${dto.peakDbfs}" }

        return Detection(
            id = UUID.fromString(dto.id),
            sequenceNumber = dto.sequenceNumber,
            deviceId = UUID.fromString(dto.deviceId),
            peakDbfs = dto.peakDbfs,
            latitude = latitude,
            longitude = longitude,
            gpsAccuracy = dto.gpsAccuracy,
            receivedAtUtc = Instant.parse(dto.receivedAtUtc),
            peakTimeClient = dto.peakTimeClient?.let(Instant::parse),
            hotspotId = dto.hotspotId?.let(UUID::fromString)
        )
    }
}
