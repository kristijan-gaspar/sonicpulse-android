package hr.sonicpulse.app.domain.model

/** Why a detection was not submitted or was rejected (plan §2.9's outcome table, plus non-HTTP boundaries). */
sealed interface SubmissionFailureReason {
    data object NoLocation : SubmissionFailureReason
    data object StaleLocation : SubmissionFailureReason
    data object InaccurateLocation : SubmissionFailureReason

    data object LocalStorageError : SubmissionFailureReason
    data object NetworkError : SubmissionFailureReason
    data object Cancelled : SubmissionFailureReason

    data object BadRequest : SubmissionFailureReason
    data object Unauthorized : SubmissionFailureReason

    data class RateLimited(val retryAfterSeconds: Long?) : SubmissionFailureReason

    data class ClientError(val httpCode: Int) : SubmissionFailureReason

    data class ServerError(val httpCode: Int) : SubmissionFailureReason

    data class UnexpectedHttpStatus(val httpCode: Int) : SubmissionFailureReason
}
