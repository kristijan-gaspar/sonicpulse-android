package hr.sonicpulse.app.data.remote

/** Maps an HTTP response (status code + Retry-After header) to a [SubmissionOutcome] per plan §2.9. */
object SubmissionOutcomeMapper {

    fun map(httpCode: Int, retryAfterHeader: String?): SubmissionOutcome = when (httpCode) {
        201 -> SubmissionOutcome.Success
        400 -> SubmissionOutcome.BadRequest
        401 -> SubmissionOutcome.Unauthorized
        429 -> SubmissionOutcome.RateLimited(retryAfterHeader?.toLongOrNull())
        in 402..499 -> SubmissionOutcome.ClientError(httpCode)
        in 500..599 -> SubmissionOutcome.ServerError(httpCode)
        else -> SubmissionOutcome.ClientError(httpCode)
    }
}
