package hr.sonicpulse.app.domain.model

import java.time.Instant
import java.util.UUID

data class SessionDetection(
    val localEventId: UUID,
    val peakDbfs: Double,
    val peakTimeClient: Instant
)
