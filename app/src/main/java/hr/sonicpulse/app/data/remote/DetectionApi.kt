package hr.sonicpulse.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface DetectionApi {
    @POST("api/detections")
    suspend fun submitDetection(@Body body: DetectionRequestDto): Response<Unit>

    /** `cursor == null` omits the query parameter entirely (Retrofit's default for a null
     * @Query value) — the backend then returns the first, newest page. */
    @GET("api/devices/{deviceId}/detections")
    suspend fun getDetectionHistory(
        @Path("deviceId") deviceId: String,
        @Query("cursor") cursor: Long?,
        @Query("limit") limit: Int
    ): DetectionHistoryPageDto
}
