package hr.sonicpulse.app.domain.model

/** Local-vs-sent status of one detection (plan §2.9) — distinct from §2.10's "Grupirano" concern. */
sealed interface SubmissionStatus {
    data object Pending : SubmissionStatus
    data object Sent : SubmissionStatus
    data class Failed(val reason: SubmissionFailureReason) : SubmissionStatus
}
