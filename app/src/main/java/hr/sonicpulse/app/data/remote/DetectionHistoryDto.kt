package hr.sonicpulse.app.data.remote

import kotlinx.serialization.Serializable

/** One item of `GET /api/devices/{deviceId}/detections` (backend contract). UUID and timestamp
 * fields stay `String` at the serialization boundary — [DetectionHistoryMapper] parses them into
 * the strongly typed domain model. */
@Serializable
data class DetectionHistoryItemDto(
    val id: String,
    val sequenceNumber: Long,
    val deviceId: String,
    val peakDbfs: Double,
    val latitude: Double,
    val longitude: Double,
    val gpsAccuracy: Double,
    val receivedAtUtc: String,
    val peakTimeClient: String? = null,
    val hotspotId: String? = null
)

/** The full paged response — `nextCursor == null` means there are no more pages. */
@Serializable
data class DetectionHistoryPageDto(
    val items: List<DetectionHistoryItemDto>,
    val nextCursor: Long? = null
)
