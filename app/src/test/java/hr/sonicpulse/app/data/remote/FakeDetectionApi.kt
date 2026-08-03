package hr.sonicpulse.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import retrofit2.Response

fun fakeHttpResponse(code: Int, retryAfterHeader: String? = null): Response<Unit> {
    val rawResponseBuilder = okhttp3.Response.Builder()
        .code(code)
        .message("")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("http://localhost/api/detections").build())
    if (retryAfterHeader != null) {
        rawResponseBuilder.header("Retry-After", retryAfterHeader)
    }
    val rawResponse = rawResponseBuilder.build()
    return if (code in 200..299) {
        Response.success(Unit, rawResponse)
    } else {
        Response.error("".toResponseBody("application/json".toMediaType()), rawResponse)
    }
}

class FakeDetectionApi(
    private val responseProvider: (DetectionRequestDto) -> Response<Unit> = { fakeHttpResponse(201) }
) : DetectionApi {

    val requests = mutableListOf<DetectionRequestDto>()
    var throwOnSubmit: Throwable? = null

    override suspend fun submitDetection(body: DetectionRequestDto): Response<Unit> {
        requests += body
        throwOnSubmit?.let { throw it }
        return responseProvider(body)
    }

    val historyRequests = mutableListOf<Triple<String, Long?, Int>>()
    var historyResponse: DetectionHistoryPageDto = DetectionHistoryPageDto(items = emptyList(), nextCursor = null)
    var throwOnGetHistory: Throwable? = null

    override suspend fun getDetectionHistory(deviceId: String, cursor: Long?, limit: Int): DetectionHistoryPageDto {
        historyRequests += Triple(deviceId, cursor, limit)
        throwOnGetHistory?.let { throw it }
        return historyResponse
    }
}
