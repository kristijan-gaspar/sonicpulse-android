package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.Hotspot

class FakeHotspotsRepository : HotspotsRepository {

    /** Keyed by the `sinceHours` a call was made with. */
    var hotspots: Map<Int, List<Hotspot>> = emptyMap()
    var throwOnGetHotspots: Throwable? = null
    val requestedSinceHours = mutableListOf<Int>()

    override suspend fun getHotspots(sinceHours: Int): List<Hotspot> {
        requestedSinceHours += sinceHours
        throwOnGetHotspots?.let { throw it }
        return hotspots[sinceHours] ?: emptyList()
    }
}
