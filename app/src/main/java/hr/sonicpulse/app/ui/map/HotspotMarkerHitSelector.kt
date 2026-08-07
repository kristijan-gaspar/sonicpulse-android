package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Hit-testing for the fixed-size, screen-space centroid marker (plan item 2) — independent of
 * [HotspotHitSelector], which hit-tests the *geographic* polygon/radius. A marker's visual size
 * never changes with zoom (see [MapScreen]'s `CircleLayer` `radius`, always the same dp value), so
 * its clickable area has to be converted from that fixed dp radius into an equivalent geographic
 * radius that shrinks as the user zooms in and grows as they zoom out — otherwise a visually large,
 * zoomed-out marker would still only be clickable across a few real-world meters (plan item 4).
 */
object HotspotMarkerHitSelector {

    /** Reference tile edge length in dp — the standard Web Mercator tile-pyramid convention (the
     * whole world is `TILE_SIZE_DP * 2^zoom` dp wide at a given zoom), also used internally by
     * MapLibre itself. Verified against the pinned maplibre-compose 0.13.0 sources:
     * `CircleLayer`'s `radius` and the `DpOffset` MapLibre hands back from a click are both already
     * expressed in density-independent dp, and `AndroidMapAdapter.metersPerDpAtLatitude` passes the
     * native SDK's own per-pixel resolution straight through with no extra device-density factor —
     * so this formula needs none either. */
    private const val TILE_SIZE_DP = 256.0

    /** Default tap-tolerance radius in dp — a little larger than the marker's own visual radius (12
     * dp in [MapScreen]) so tapping close to, not just exactly inside, the visible marker still
     * registers. */
    const val DEFAULT_TAP_RADIUS_DP = 20.0

    /** The nearest marker within its tap tolerance wins; an exact tie breaks the same deterministic
     * way as [HotspotHitSelector.select] — both share [HotspotHitSelector.nearestWithinTolerance]. */
    fun select(
        clickLatitude: Double,
        clickLongitude: Double,
        hotspots: List<Hotspot>,
        zoom: Double,
        tapRadiusDp: Double = DEFAULT_TAP_RADIUS_DP
    ): Hotspot? =
        HotspotHitSelector.nearestWithinTolerance(clickLatitude, clickLongitude, hotspots) { hotspot ->
            toleranceMeters(hotspot.latitude, zoom, tapRadiusDp)
        }

    /** [tapRadiusDp] converted to an equivalent geographic radius in meters, at [latitudeDegrees]
     * and [zoom]. */
    fun toleranceMeters(latitudeDegrees: Double, zoom: Double, tapRadiusDp: Double): Double =
        tapRadiusDp * metersPerDp(latitudeDegrees, zoom)

    /** Standard Web Mercator ground resolution (meters per dp): halves each time [zoom] increases
     * by 1, and shrinks by `cos(latitude)` away from the equator (Mercator's areal distortion).
     * Reuses [HotspotGeometry.EARTH_RADIUS_METERS] rather than introducing the (very slightly
     * different) WGS84 semi-major axis just for this formula — the sub-0.2% gap between the two is
     * irrelevant for a UI tap-tolerance radius. */
    fun metersPerDp(latitudeDegrees: Double, zoom: Double): Double {
        val circumferenceMeters = 2.0 * PI * HotspotGeometry.EARTH_RADIUS_METERS
        return (circumferenceMeters * cos(Math.toRadians(latitudeDegrees))) / (TILE_SIZE_DP * 2.0.pow(zoom))
    }
}
