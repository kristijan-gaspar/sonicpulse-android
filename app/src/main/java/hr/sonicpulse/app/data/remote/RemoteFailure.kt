package hr.sonicpulse.app.data.remote

import java.io.IOException
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * A remote (HTTP/network/parsing) failure classified into the buckets the UI actually needs to
 * distinguish — shared by every repository that calls the backend via a plain Retrofit suspend
 * function returning its body directly (history, hotspots), where a non-2xx response surfaces as
 * a thrown [HttpException] rather than a [retrofit2.Response] to inspect. `POST /api/detections`
 * (submission) never throws for a non-2xx status — it returns `Response<Unit>` and is classified
 * separately by [SubmissionOutcomeMapper], which applies the identical 401/403/429/4xx/5xx rules
 * below directly against the status code; the two are independent implementations of the same
 * decision, not literally shared code, since submission's outcome model also carries submission-
 * only cases (e.g. a 400 is specifically `BadRequest`, not a generic `ClientError`) that don't
 * belong on a type shared with read-only endpoints.
 */
sealed interface RemoteFailure {
    /** 401 or 403 — an authentication/server-configuration problem (e.g. a missing or invalid API
     * key), never something the app or the user can fix by retrying. */
    data object Unauthorized : RemoteFailure
    data class RateLimited(val retryAfterSeconds: Long?) : RemoteFailure
    data class ClientError(val httpCode: Int) : RemoteFailure
    data class ServerError(val httpCode: Int) : RemoteFailure
    data class UnexpectedHttpStatus(val httpCode: Int) : RemoteFailure

    /** No HTTP response was ever received — connectivity, timeout, DNS, TLS, etc. */
    data object Network : RemoteFailure

    /** A response was received but couldn't be parsed as JSON, or failed the mapper's own domain
     * validation (e.g. an out-of-range coordinate) — a backend/protocol problem either way, never
     * silently coerced into a default value. */
    data object InvalidResponse : RemoteFailure
}

/**
 * Classifies a failure caught from a call to [DetectionApi]/[HotspotApi]. Never called with a
 * coroutine [kotlinx.coroutines.CancellationException] — every caller catches and rethrows that
 * first, before this ever runs, so cancellation keeps propagating normally. Exposes only the
 * classification, never [Throwable.message]/a response body/the API key/the base URL — those stay
 * out of anything this returns to a ViewModel or the UI.
 */
object RemoteFailureClassifier {

    private const val RETRY_AFTER_HEADER = "Retry-After"

    fun classify(throwable: Throwable): RemoteFailure = when (throwable) {
        is HttpException -> classifyHttpCode(
            httpCode = throwable.code(),
            retryAfterHeader = throwable.response()?.headers()?.get(RETRY_AFTER_HEADER)
        )
        // SerializationException extends IllegalArgumentException, so it must be checked first —
        // both a malformed-JSON parse failure and a mapper's require() validation failure land in
        // the same InvalidResponse bucket regardless of which one actually threw.
        is SerializationException -> RemoteFailure.InvalidResponse
        is IllegalArgumentException -> RemoteFailure.InvalidResponse
        is DateTimeParseException -> RemoteFailure.InvalidResponse
        is IOException -> RemoteFailure.Network
        else -> throw throwable
    }

    private fun classifyHttpCode(httpCode: Int, retryAfterHeader: String?): RemoteFailure = when {
        httpCode == 401 || httpCode == 403 -> RemoteFailure.Unauthorized
        httpCode == 429 -> RemoteFailure.RateLimited(parseRetryAfterSeconds(retryAfterHeader))
        httpCode in 400..499 -> RemoteFailure.ClientError(httpCode)
        httpCode in 500..599 -> RemoteFailure.ServerError(httpCode)
        else -> RemoteFailure.UnexpectedHttpStatus(httpCode)
    }

    /** Accepts a non-negative integer number of seconds; missing, malformed (incl. HTTP-date) or
     * negative values map to null — mirrors [SubmissionOutcomeMapper]'s identical parsing. */
    private fun parseRetryAfterSeconds(header: String?): Long? =
        header?.toLongOrNull()?.takeIf { it >= 0 }
}
