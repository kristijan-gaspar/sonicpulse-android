package hr.sonicpulse.app.data.remote

class FakeHotspotApi : HotspotApi {

    var response: List<HotspotDto> = emptyList()
    var throwOnGetHotspots: Throwable? = null
    val requestedSinceHours = mutableListOf<Int>()

    override suspend fun getHotspots(sinceHours: Int): List<HotspotDto> {
        requestedSinceHours += sinceHours
        throwOnGetHotspots?.let { throw it }
        return response
    }
}
