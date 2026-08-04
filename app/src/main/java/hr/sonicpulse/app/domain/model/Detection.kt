package hr.sonicpulse.app.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A single detection as returned by the backend's device-history endpoint — distinct from
 * [SessionDetection], which models one in-progress monitoring session's local/sent state and
 * never carries [id]/[sequenceNumber]/[hotspotId] (those only exist once the backend has
 * persisted and possibly grouped the event).
 */
data class Detection(
    val id: UUID,
    val sequenceNumber: Long,
    val deviceId: UUID,
    val peakDbfs: Double,
    val latitude: Double,
    val longitude: Double,
    val gpsAccuracy: Double,
    val receivedAtUtc: Instant,
    val peakTimeClient: Instant?,
    val hotspotId: UUID?
)
