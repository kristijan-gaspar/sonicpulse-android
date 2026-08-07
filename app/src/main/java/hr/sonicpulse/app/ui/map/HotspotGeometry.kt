package hr.sonicpulse.app.ui.map

import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One coordinate in GeoJSON order — (longitude, latitude), never the reverse. */
data class GeoPosition(val longitude: Double, val latitude: Double)

/** A closed geodesic ring (first == last coordinate) around one hotspot's centroid, plus the
 * properties a GeoJSON feature needs for rendering and identification. Independent of Compose and
 * MapLibre — a separate, thin serializer turns a list of these into a GeoJSON `FeatureCollection`
 * JSON string for [org.maplibre.compose.sources.GeoJsonData.JsonString]. */
data class HotspotPolygon(
    val hotspotId: UUID,
    val deviceCount: Int,
    val confidence: Int,
    val ring: List<GeoPosition>
)

/**
 * Generates a geodesic polygon around a hotspot centroid using the destination-point formula on a
 * spherical Earth (algorithm doc-equivalent rationale: `radiusMeters` is a real geographic
 * distance and must stay visually accurate at every zoom level, which a fixed-pixel screen-space
 * circle cannot guarantee).
 */
object HotspotGeometry {

    /** Mean Earth radius in metres (WGS84 mean radius), matching the algorithm document's own
     * haversine constant so geometry and grouping stay consistent with the backend's model. */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Vertices in the ring excluding the closing duplicate — 64 segments is enough to look
     * smooth at any zoom level actually used on the Map screen without being wasteful to render. */
    const val SEGMENTS = 64

    /** A zero or near-zero backend `radiusMeters` would otherwise collapse the ring to (or near) a
     * single point — invisible, and (since a hotspot polygon's click handling is the *rendered*
     * fill's own `onClick`, via native hit-testing — see [MapScreen]) effectively unclickable too.
     * This is a rendering/hit-testing floor only: the real, possibly-zero radius is preserved
     * unchanged in [hr.sonicpulse.app.domain.model.Hotspot] and the detail sheet — see plan item 12. */
    const val MIN_EFFECTIVE_RADIUS_METERS = 25.0

    /**
     * [applyMinimumRadius] defaults to `true` for every caller that actually draws or hit-tests
     * this ring on the map. [HotspotCamera] is the one deliberate exception: it passes `false` so
     * its own degenerate-span detection (KeepCurrent-vs-Bounds framing) keeps seeing hotspots'
     * real, possibly-zero radii — the visual/hit-testing floor here must not silently change
     * which hotspot datasets the camera treats as "nothing meaningful to frame".
     */
    fun polygonFor(
        hotspotId: UUID,
        centerLatitude: Double,
        centerLongitude: Double,
        radiusMeters: Double,
        deviceCount: Int,
        confidence: Int,
        applyMinimumRadius: Boolean = true
    ): HotspotPolygon {
        val effectiveRadiusMeters = if (applyMinimumRadius) maxOf(radiusMeters, MIN_EFFECTIVE_RADIUS_METERS) else radiusMeters
        val ring = ArrayList<GeoPosition>(SEGMENTS + 1)
        val first = destinationPoint(centerLatitude, centerLongitude, effectiveRadiusMeters, bearingDegrees = 0.0)
        ring += first
        for (i in 1 until SEGMENTS) {
            val bearing = 360.0 * i / SEGMENTS
            ring += destinationPoint(centerLatitude, centerLongitude, effectiveRadiusMeters, bearing)
        }
        ring += first // close the ring by repeating the first coordinate as the final coordinate

        return HotspotPolygon(
            hotspotId = hotspotId,
            deviceCount = deviceCount,
            confidence = confidence,
            ring = ring
        )
    }

    /** Standard spherical destination-point formula: given a start point, an angular distance
     * (`radiusMeters / earthRadius`) and a bearing, returns the destination point. Clamps the
     * `asin` argument to `[-1, 1]` — floating-point error can push it a hair outside that range
     * for points very close to a pole, which would otherwise make `asin` return `NaN`. */
    private fun destinationPoint(
        centerLatitude: Double,
        centerLongitude: Double,
        radiusMeters: Double,
        bearingDegrees: Double
    ): GeoPosition {
        val angularDistance = radiusMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(centerLatitude)
        val lon1 = Math.toRadians(centerLongitude)

        val sinLat2 = (sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
            .coerceIn(-1.0, 1.0)
        val lat2 = asin(sinLat2)
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sinLat2
        )

        return GeoPosition(longitude = normalizeLongitudeDegrees(Math.toDegrees(lon2)), latitude = Math.toDegrees(lat2))
    }

    /** Wraps into `-180.0..180.0` — needed for hotspots near the anti-meridian, where the raw
     * destination-point longitude can land outside that range. */
    private fun normalizeLongitudeDegrees(longitude: Double): Double {
        var normalized = (longitude + 180.0) % 360.0
        if (normalized < 0.0) normalized += 360.0
        return normalized - 180.0
    }
}
