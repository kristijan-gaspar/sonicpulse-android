package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.remote.HotspotApi
import hr.sonicpulse.app.data.remote.HotspotMapper
import hr.sonicpulse.app.domain.model.Hotspot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultHotspotsRepository @Inject constructor(
    private val hotspotApi: HotspotApi
) : HotspotsRepository {

    override suspend fun getHotspots(sinceHours: Int): List<Hotspot> =
        hotspotApi.getHotspots(sinceHours = sinceHours).map(HotspotMapper::toDomain)
}
