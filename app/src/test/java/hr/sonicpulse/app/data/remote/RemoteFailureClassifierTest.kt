package hr.sonicpulse.app.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response as OkHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RemoteFailureClassifierTest {

    private fun httpException(code: Int, retryAfter: String? = null): HttpException {
        val builder = OkHttpResponse.Builder()
            .code(code)
            .message("test")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/").build())
        retryAfter?.let { builder.header("Retry-After", it) }
        val body = "{}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Unit>(body, builder.build()))
    }

    @Test
    fun `401 classifies as Unauthorized`() {
        assertEquals(RemoteFailure.Unauthorized, RemoteFailureClassifier.classify(httpException(401)))
    }

    @Test
    fun `403 classifies as Unauthorized`() {
        assertEquals(RemoteFailure.Unauthorized, RemoteFailureClassifier.classify(httpException(403)))
    }

    @Test
    fun `404 classifies as ClientError`() {
        assertEquals(RemoteFailure.ClientError(404), RemoteFailureClassifier.classify(httpException(404)))
    }

    @Test
    fun `429 classifies as RateLimited with a parsed Retry-After`() {
        assertEquals(
            RemoteFailure.RateLimited(30L),
            RemoteFailureClassifier.classify(httpException(429, retryAfter = "30"))
        )
    }

    @Test
    fun `429 without a Retry-After header classifies as RateLimited with a null delay`() {
        assertEquals(RemoteFailure.RateLimited(null), RemoteFailureClassifier.classify(httpException(429)))
    }

    @Test
    fun `429 with a malformed Retry-After header classifies as RateLimited with a null delay`() {
        assertEquals(
            RemoteFailure.RateLimited(null),
            RemoteFailureClassifier.classify(httpException(429, retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT"))
        )
    }

    @Test
    fun `500 classifies as ServerError`() {
        assertEquals(RemoteFailure.ServerError(500), RemoteFailureClassifier.classify(httpException(500)))
    }

    @Test
    fun `503 classifies as ServerError`() {
        assertEquals(RemoteFailure.ServerError(503), RemoteFailureClassifier.classify(httpException(503)))
    }

    @Test
    fun `an unexpected 3xx-shaped code classifies as UnexpectedHttpStatus`() {
        assertEquals(RemoteFailure.UnexpectedHttpStatus(302), RemoteFailureClassifier.classify(httpException(302)))
    }

    @Test
    fun `an IOException classifies as Network`() {
        assertEquals(RemoteFailure.Network, RemoteFailureClassifier.classify(SocketTimeoutException("timeout")))
    }

    @Test
    fun `a plain IOException classifies as Network`() {
        assertEquals(RemoteFailure.Network, RemoteFailureClassifier.classify(IOException("no connection")))
    }

    @Test
    fun `a SerializationException classifies as InvalidResponse`() {
        assertEquals(RemoteFailure.InvalidResponse, RemoteFailureClassifier.classify(SerializationException("bad json")))
    }

    @Test
    fun `an IllegalArgumentException from a mapper's own validation classifies as InvalidResponse`() {
        assertEquals(
            RemoteFailure.InvalidResponse,
            RemoteFailureClassifier.classify(IllegalArgumentException("Invalid latitude: 999.0"))
        )
    }

    @Test
    fun `a DateTimeParseException classifies as InvalidResponse`() {
        val exception = try {
            java.time.Instant.parse("not-a-timestamp")
            error("test setup invalid: expected DateTimeParseException")
        } catch (e: DateTimeParseException) {
            e
        }
        assertEquals(RemoteFailure.InvalidResponse, RemoteFailureClassifier.classify(exception))
    }

    @Test
    fun `an unrelated exception type classifies as Unknown`() {
        val exception = IllegalStateException("unexpected bug")

        val result = RemoteFailureClassifier.classify(exception)

        assertEquals(RemoteFailure.Unknown, result)
    }

    @Test
    fun `a CancellationException is rethrown, never classified`() {
        val original = CancellationException("cancelled")
        assertThrows(CancellationException::class.java) {
            RemoteFailureClassifier.classify(original)
        }
    }
}
