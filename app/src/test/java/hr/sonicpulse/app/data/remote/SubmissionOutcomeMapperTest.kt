package hr.sonicpulse.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionOutcomeMapperTest {

    @Test
    fun `201 maps to Success`() {
        assertEquals(SubmissionOutcome.Success, SubmissionOutcomeMapper.map(201, retryAfterHeader = null))
    }

    @Test
    fun `400 maps to BadRequest`() {
        assertEquals(SubmissionOutcome.BadRequest, SubmissionOutcomeMapper.map(400, retryAfterHeader = null))
    }

    @Test
    fun `401 maps to Unauthorized`() {
        assertEquals(SubmissionOutcome.Unauthorized, SubmissionOutcomeMapper.map(401, retryAfterHeader = null))
    }

    @Test
    fun `429 without Retry-After maps to RateLimited with null seconds`() {
        assertEquals(
            SubmissionOutcome.RateLimited(retryAfterSeconds = null),
            SubmissionOutcomeMapper.map(429, retryAfterHeader = null)
        )
    }

    @Test
    fun `429 with numeric Retry-After maps to RateLimited with parsed seconds`() {
        assertEquals(
            SubmissionOutcome.RateLimited(retryAfterSeconds = 30L),
            SubmissionOutcomeMapper.map(429, retryAfterHeader = "30")
        )
    }

    @Test
    fun `429 with non-numeric Retry-After maps to RateLimited with null seconds`() {
        assertEquals(
            SubmissionOutcome.RateLimited(retryAfterSeconds = null),
            SubmissionOutcomeMapper.map(429, retryAfterHeader = "Wed, 21 Oct 2026 07:28:00 GMT")
        )
    }

    @Test
    fun `other 4xx codes map to ClientError carrying the http code`() {
        listOf(403, 404, 409, 422, 418).forEach { code ->
            assertEquals(
                "code $code",
                SubmissionOutcome.ClientError(code),
                SubmissionOutcomeMapper.map(code, retryAfterHeader = null)
            )
        }
    }

    @Test
    fun `5xx codes map to ServerError carrying the http code`() {
        listOf(500, 502, 503).forEach { code ->
            assertEquals(
                "code $code",
                SubmissionOutcome.ServerError(code),
                SubmissionOutcomeMapper.map(code, retryAfterHeader = null)
            )
        }
    }
}
