package hr.sonicpulse.app.domain.model

import hr.sonicpulse.app.data.location.LocationSnapshot
import java.time.Instant
import java.util.UUID

data class SessionDetection(
    val localEventId: UUID,
    val peakDbfs: Double,
    val peakTimeClient: Instant,
    val location: LocationSnapshot.Valid,
    val submissionStatus: SubmissionStatus = SubmissionStatus.Pending
)
