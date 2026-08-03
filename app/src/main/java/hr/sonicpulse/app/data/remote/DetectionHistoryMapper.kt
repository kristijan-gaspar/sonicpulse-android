package hr.sonicpulse.app.data.remote

import hr.sonicpulse.app.domain.model.Detection
import java.time.Instant
import java.util.UUID

/**
 * Maps one [DetectionHistoryItemDto] to the domain [Detection]. Deliberately does not catch
 * [IllegalArgumentException] (malformed UUID) or [java.time.format.DateTimeParseException]
 * (malformed timestamp) — a malformed field is malformed backend data, a real protocol failure,
 * not something to silently drop or null out.
 */
object DetectionHistoryMapper {
    fun toDomain(dto: DetectionHistoryItemDto): Detection = Detection(
        id = UUID.fromString(dto.id),
        sequenceNumber = dto.sequenceNumber,
        deviceId = UUID.fromString(dto.deviceId),
        peakDbfs = dto.peakDbfs,
        latitude = dto.latitude,
        longitude = dto.longitude,
        gpsAccuracy = dto.gpsAccuracy,
        receivedAtUtc = Instant.parse(dto.receivedAtUtc),
        peakTimeClient = dto.peakTimeClient?.let(Instant::parse),
        hotspotId = dto.hotspotId?.let(UUID::fromString)
    )
}
