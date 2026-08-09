package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import kotlin.math.ln
import kotlin.math.min
import org.maplibre.spatialk.geojson.BoundingBox

/** What the camera should do for a given hotspot dataset — independent of Compose/MapLibre camera
 * types except [BoundingBox] itself (a plain value class, not a runtime/animation API). */
sealed interface HotspotCameraTarget {
    /** Nothing to fit — leave whatever camera position is already active untouched (§8: the
     * Croatia default on first load, or wherever the user last panned to). */
    data object KeepCurrent : HotspotCameraTarget

    data class Center(val latitude: Double, val longitude: Double, val zoom: Double) : HotspotCameraTarget

    data class Bounds(val boundingBox: BoundingBox) : HotspotCameraTarget
}

/**
 * Derives a [HotspotCameraTarget] from a committed hotspot dataset.
 *
 * A single-hotspot dataset is always framed as [HotspotCameraTarget.Bounds], never via the
 * degenerate-span path below: its framing radius is `maxOf(radiusMeters,
 * [SINGLE_HOTSPOT_MIN_CAMERA_RADIUS_METERS])`, so even a genuinely zero-radius lone hotspot gets a
 * real, non-degenerate span to fit — giving the camera sensible surrounding context instead of
 * zooming in on literally nothing. This is a deliberate, hotspot-count-based exception to the
 * span-magnitude rule below.
 *
 * Two remaining cases are routed to [HotspotCameraTarget.Center] instead of
 * [HotspotCameraTarget.Bounds]:
 *
 * - a degenerate span — two or more hotspots whose points collapse to essentially one geographic
 *   location, e.g. several co-located zero-radius hotspots, or an extremely small near-zero span
 *   across the group. Detected by span magnitude ([CAMERA_SPAN_EPSILON_DEGREES]) after each
 *   hotspot's own framing radius has been applied — never reachable for a single hotspot, since its
 *   minimum framing radius above always produces a real span; a zero-area `BoundingBox` must never
 *   reach MapLibre's camera fit (§8);
 * - any dataset whose minimal longitude span crosses the anti-meridian (§9) — this project could
 *   not verify whether MapLibre Compose 0.13.0's camera fit correctly interprets a `BoundingBox`
 *   with `west > east` as "wraps around" rather than an inverted/empty box, and the task's own
 *   instructions are explicit that an unverified assumption here must not be guessed. A
 *   circular-center-based [HotspotCameraTarget.Center] sidesteps the question entirely.
 */
object HotspotCamera {

    /** Sensible "close" zoom for a degenerate span with no meaningful area to frame. */
    const val SINGLE_POINT_ZOOM = 16.0

    /** Below this, both latitude and longitude spans are treated as "no meaningful area" rather
     * than a genuine (if tiny) bounding box — independent of how many hotspots contributed to it. */
    const val CAMERA_SPAN_EPSILON_DEGREES = 1e-9

    private const val MIN_ZOOM = 2.0
    private const val MAX_ZOOM = 16.0

    /** The minimum camera-framing radius applied to a single-hotspot dataset regardless of its
     * actual `radiusMeters` — see the class KDoc. `internal` so the test suite can assert against
     * it directly instead of duplicating the literal. */
    internal const val SINGLE_HOTSPOT_MIN_CAMERA_RADIUS_METERS = 500.0

    fun targetFor(hotspots: List<Hotspot>): HotspotCameraTarget {
        if (hotspots.isEmpty()) return HotspotCameraTarget.KeepCurrent

        val polygons = hotspots.map {
            HotspotGeometry.polygonFor(
                hotspotId = it.id,
                centerLatitude = it.latitude,
                centerLongitude = it.longitude,
                radiusMeters = if (hotspots.size == 1) {
                    maxOf(it.radiusMeters, SINGLE_HOTSPOT_MIN_CAMERA_RADIUS_METERS)
                } else {
                    it.radiusMeters
                },
                deviceCount = it.deviceCount,
                confidence = it.confidence,
                applyMinimumRadius = false
            )
        }

        val span = HotspotBounds.compute(polygons) ?: return HotspotCameraTarget.KeepCurrent

        val latitudeSpanDegrees = span.northLatitude - span.southLatitude
        val isDegenerate = latitudeSpanDegrees < CAMERA_SPAN_EPSILON_DEGREES &&
            span.longitudeSpanDegrees < CAMERA_SPAN_EPSILON_DEGREES
        if (isDegenerate) {
            return HotspotCameraTarget.Center(
                latitude = (span.northLatitude + span.southLatitude) / 2.0,
                longitude = span.centerLongitude,
                zoom = SINGLE_POINT_ZOOM
            )
        }

        if (span.crossesAntimeridian) {
            return HotspotCameraTarget.Center(
                latitude = (span.northLatitude + span.southLatitude) / 2.0,
                longitude = span.centerLongitude,
                zoom = zoomForSpans(span.longitudeSpanDegrees, latitudeSpanDegrees)
            )
        }

        return HotspotCameraTarget.Bounds(
            BoundingBox(
                west = span.westLongitude,
                south = span.southLatitude,
                east = span.eastLongitude,
                north = span.northLatitude
            )
        )
    }

    private fun zoomForSpans(longitudeSpanDegrees: Double, latitudeSpanDegrees: Double): Double =
        min(zoomForSpan(longitudeSpanDegrees), zoomForSpan(latitudeSpanDegrees))

    private fun zoomForSpan(spanDegrees: Double): Double {
        if (spanDegrees <= 0.0) return MAX_ZOOM
        val zoom = ln(360.0 / spanDegrees) / ln(2.0)
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
