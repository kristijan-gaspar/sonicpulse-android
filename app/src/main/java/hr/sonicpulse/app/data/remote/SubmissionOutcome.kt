package hr.sonicpulse.app.data.remote

/** Result of mapping an HTTP response to a classification (plan §2.9's outcome table). */
sealed class SubmissionOutcome {
    data object Success : SubmissionOutcome()
    data object BadRequest : SubmissionOutcome()
    data object Unauthorized : SubmissionOutcome()
    data class RateLimited(val retryAfterSeconds: Long?) : SubmissionOutcome()
    data class ClientError(val httpCode: Int) : SubmissionOutcome()
    data class ServerError(val httpCode: Int) : SubmissionOutcome()
    data class UnexpectedHttpStatus(val httpCode: Int) : SubmissionOutcome()
}
