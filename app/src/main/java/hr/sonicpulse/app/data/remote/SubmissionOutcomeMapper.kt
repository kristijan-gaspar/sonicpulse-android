package hr.sonicpulse.app.data.remote

/** Maps an HTTP response (status code + Retry-After header) to a [SubmissionOutcome] per plan §2.9. */
object SubmissionOutcomeMapper {

    fun map(httpCode: Int, retryAfterHeader: String?): SubmissionOutcome = when {
        httpCode in 200..299 -> SubmissionOutcome.Success
        httpCode == 400 -> SubmissionOutcome.BadRequest
        httpCode == 401 || httpCode == 403 -> SubmissionOutcome.Unauthorized
        httpCode == 429 -> SubmissionOutcome.RateLimited(parseRetryAfterSeconds(retryAfterHeader))
        httpCode in 400..499 -> SubmissionOutcome.ClientError(httpCode)
        httpCode in 500..599 -> SubmissionOutcome.ServerError(httpCode)
        else -> SubmissionOutcome.UnexpectedHttpStatus(httpCode)
    }

    /** Accepts a non-negative integer number of seconds; missing, malformed (incl. HTTP-date) or negative values map to null. */
    private fun parseRetryAfterSeconds(header: String?): Long? =
        header?.toLongOrNull()?.takeIf { it >= 0 }
}
