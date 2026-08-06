package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure hit-testing for a map click against the currently loaded hotspots — deliberately independent
 * of MapLibre's own per-layer feature click ordering (undocumented/unspecified when polygons
 * overlap). [MapScreen] feeds this the geographic position from `MaplibreMap`'s own `onMapClick`,
 * never a per-layer click callback.
 */
object HotspotHitSelector {

    /** A hotspot is a candidate when the click falls on or inside its *effective* radius (`<=`,
     * so a boundary click counts as inside) — [HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS] at
     * minimum, the same floor [HotspotGeometry.polygonFor] renders with, so the clickable area
     * always matches what's actually drawn even for a zero/near-zero backend radius. Among
     * overlapping candidates, the nearest centroid wins; an exact tie falls back to hotspot UUID
     * for a deterministic, order-independent result. */
    fun select(clickLatitude: Double, clickLongitude: Double, hotspots: List<Hotspot>): Hotspot? =
        hotspots
            .map { it to distanceMeters(clickLatitude, clickLongitude, it.latitude, it.longitude) }
            .filter { (hotspot, distance) ->
                distance <= maxOf(hotspot.radiusMeters, HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS)
            }
            .minWithOrNull(compareBy({ it.second }, { it.first.id }))
            ?.first

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = HotspotGeometry.EARTH_RADIUS_METERS
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val a = (sinDLat * sinDLat) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * (sinDLon * sinDLon)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
