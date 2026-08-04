package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.Hotspot

/**
 * Remote data access only — one fetch per call, no stored range/session state, no dedup, no
 * caching, no polling. [hr.sonicpulse.app.ui.map.MapViewModel] owns everything about the screen
 * session, mirroring [DetectionsRepository]'s split with [hr.sonicpulse.app.ui.detections.DetectionsViewModel].
 */
interface HotspotsRepository {
    suspend fun getHotspots(sinceHours: Int): List<Hotspot>
}
