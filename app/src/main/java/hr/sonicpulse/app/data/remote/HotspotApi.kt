package hr.sonicpulse.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface HotspotApi {
    /** The backend clamps [sinceHours] to 1..168; the client only ever sends 24, 72 or 168 (see
     * [hr.sonicpulse.app.ui.map.HotspotTimeRange]). Response is a bare JSON array. */
    @GET("api/hotspots")
    suspend fun getHotspots(@Query("sinceHours") sinceHours: Int): List<HotspotDto>
}
