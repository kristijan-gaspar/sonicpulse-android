package hr.sonicpulse.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface DetectionApi {
    @POST("api/detections")
    suspend fun submitDetection(@Body body: DetectionRequestDto): Response<Unit>
}
