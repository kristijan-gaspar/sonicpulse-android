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

/**
 * The single instant to use for filtering, grouping, and display (list/detail timestamps) — the
 * on-device peak instant when the backend has one (closer to when the sound actually happened),
 * falling back to [Detection.receivedAtUtc] for older records submitted before `peakTimeClient`
 * existed. Never used for list/page ordering: the backend paginates by `receivedAtUtc`, and
 * resorting client-side by this instead would break that contract — use [Detection.receivedAtUtc]
 * directly wherever ordering matters.
 */
val Detection.eventTime: Instant get() = peakTimeClient ?: receivedAtUtc
