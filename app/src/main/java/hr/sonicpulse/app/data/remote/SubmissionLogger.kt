package hr.sonicpulse.app.data.remote

/**
 * Diagnostic-only logging for submission outcomes (plan §2.9's redaction rule). Never receives
 * the API key, installation id, coordinates, a request DTO, a raw error body, or an exception
 * message that might carry any of those — only the primitive values listed below.
 */
interface SubmissionLogger {
    fun networkError()
    fun localStorageError()
    fun badRequest()
    fun unauthorized()
    fun rateLimited(retryAfterSeconds: Long?)
    fun clientError(httpCode: Int)
    fun serverError(httpCode: Int)
    fun unexpectedHttpStatus(httpCode: Int)
}
