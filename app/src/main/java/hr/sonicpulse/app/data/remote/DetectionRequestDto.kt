package hr.sonicpulse.app.data.remote

import kotlinx.serialization.Serializable

/** Request body for `POST /api/detections` (backend contract, plan §4). */
@Serializable
data class DetectionRequestDto(
    val deviceId: String,
    val peakDbfs: Double,
    val latitude: Double,
    val longitude: Double,
    val gpsAccuracy: Double,
    val peakTimeClient: String? = null
)
