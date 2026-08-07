package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Independent haversine (deliberately duplicated, not made internal-visible — same rationale as
 * [HotspotHitSelectorTest]'s own copy) — used to place a click at an exact, formula-derived
 * distance from a hotspot's centroid for boundary tests, and to double-check test setup. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = HotspotGeometry.EARTH_RADIUS_METERS
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** A longitude offset (degrees, same latitude) that is exactly [meters] away by [haversineMeters] —
 * accounts for the latitude-dependent shrinking of a longitude degree (`cos(latitude)`), unlike a
 * naive `EARTH_RADIUS_METERS`-only conversion. */
private fun longitudeOffsetForMeters(latitude: Double, meters: Double): Double =
    Math.toDegrees(meters / (HotspotGeometry.EARTH_RADIUS_METERS * cos(Math.toRadians(latitude))))

class HotspotMarkerHitSelectorTest {

    private fun hotspot(
        id: UUID = UUID.randomUUID(),
        latitude: Double = 45.8,
        longitude: Double = 16.0
    ) = Hotspot(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = 16.0,
        confidence = 84,
        deviceCount = 3,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    // --- metersPerDp / toleranceMeters ---

    @Test
    fun `metersPerDp halves each time zoom increases by 1`() {
        val atZoom10 = HotspotMarkerHitSelector.metersPerDp(latitudeDegrees = 0.0, zoom = 10.0)
        val atZoom11 = HotspotMarkerHitSelector.metersPerDp(latitudeDegrees = 0.0, zoom = 11.0)

        assertEquals(atZoom10 / 2.0, atZoom11, atZoom10 * 1e-9)
    }

    @Test
    fun `metersPerDp at zoom 0 and the equator is the whole circumference over 256 dp`() {
        // At zoom 0 the whole world is one 256 dp tile — matches the standard Web Mercator
        // tile-pyramid convention (the commonly cited "156543.03 m/px" figure uses the WGS84
        // equatorial radius instead of HotspotGeometry.EARTH_RADIUS_METERS's mean radius, hence
        // this is computed from the same constant this class actually uses, not that literal).
        val expected = (2.0 * kotlin.math.PI * HotspotGeometry.EARTH_RADIUS_METERS) / 256.0

        val result = HotspotMarkerHitSelector.metersPerDp(latitudeDegrees = 0.0, zoom = 0.0)

        assertEquals(expected, result, 1e-6)
    }

    @Test
    fun `metersPerDp shrinks away from the equator by cos(latitude)`() {
        val atEquator = HotspotMarkerHitSelector.metersPerDp(latitudeDegrees = 0.0, zoom = 12.0)
        val at60North = HotspotMarkerHitSelector.metersPerDp(latitudeDegrees = 60.0, zoom = 12.0)

        // cos(60°) == 0.5 exactly.
        assertEquals(atEquator * 0.5, at60North, atEquator * 1e-9)
    }

    @Test
    fun `toleranceMeters scales linearly with the tap radius`() {
        val double = HotspotMarkerHitSelector.toleranceMeters(latitudeDegrees = 45.0, zoom = 14.0, tapRadiusDp = 40.0)
        val single = HotspotMarkerHitSelector.toleranceMeters(latitudeDegrees = 45.0, zoom = 14.0, tapRadiusDp = 20.0)

        assertEquals(single * 2.0, double, single * 1e-9)
    }

    // --- select(): the tolerance actually depends on zoom ---

    @Test
    fun `a click several km away still selects a marker when zoomed far out`() {
        val target = hotspot()
        val farZoom = 3.0 // a whole-region view
        val distanceMeters = 50_000.0 // 50 km — comfortably inside the far-zoom tolerance, see below
        val tolerance = HotspotMarkerHitSelector.toleranceMeters(target.latitude, farZoom, HotspotMarkerHitSelector.DEFAULT_TAP_RADIUS_DP)
        check(distanceMeters < tolerance) { "test setup invalid: expected a >50 km tolerance when zoomed far out, was $tolerance" }
        val clickLongitude = target.longitude + longitudeOffsetForMeters(target.latitude, distanceMeters)
        check(abs(haversineMeters(target.latitude, target.longitude, target.latitude, clickLongitude) - distanceMeters) < 1.0)

        val result = HotspotMarkerHitSelector.select(target.latitude, clickLongitude, listOf(target), zoom = farZoom)

        assertEquals(target, result)
    }

    @Test
    fun `the same geographic click distance misses once zoomed in close`() {
        val target = hotspot()
        val closeZoom = 18.0
        val distanceMeters = 50_000.0
        val toleranceAtCloseZoom =
            HotspotMarkerHitSelector.toleranceMeters(target.latitude, closeZoom, HotspotMarkerHitSelector.DEFAULT_TAP_RADIUS_DP)
        check(distanceMeters > toleranceAtCloseZoom) { "test setup invalid: 50 km must exceed the close-zoom tolerance" }
        val clickLongitude = target.longitude + longitudeOffsetForMeters(target.latitude, distanceMeters)

        val result = HotspotMarkerHitSelector.select(target.latitude, clickLongitude, listOf(target), zoom = closeZoom)

        assertNull(result)
    }

    @Test
    fun `a click exactly on the tap-tolerance boundary is considered inside`() {
        val target = hotspot()
        val zoom = 12.0
        val tolerance = HotspotMarkerHitSelector.toleranceMeters(target.latitude, zoom, HotspotMarkerHitSelector.DEFAULT_TAP_RADIUS_DP)
        val clickLongitude = target.longitude + longitudeOffsetForMeters(target.latitude, tolerance)
        check(abs(haversineMeters(target.latitude, target.longitude, target.latitude, clickLongitude) - tolerance) < 0.01)

        val result = HotspotMarkerHitSelector.select(target.latitude, clickLongitude, listOf(target), zoom = zoom)

        assertEquals(target, result)
    }

    @Test
    fun `an empty hotspot list selects none`() {
        assertNull(HotspotMarkerHitSelector.select(45.8, 16.0, emptyList(), zoom = 12.0))
    }

    // --- select(): deterministic overlap behavior — mirrors HotspotHitSelectorTest ---

    @Test
    fun `overlapping markers choose the nearest centroid`() {
        val near = hotspot(latitude = 45.8001, longitude = 16.0)
        val far = hotspot(latitude = 45.81, longitude = 16.0)

        val result = HotspotMarkerHitSelector.select(45.8, 16.0, listOf(far, near), zoom = 10.0)

        assertEquals(near, result)
    }

    @Test
    fun `an exact distance tie uses deterministic UUID ordering`() {
        val lowerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val higherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val east = hotspot(id = higherId, latitude = 45.8, longitude = 16.001)
        val west = hotspot(id = lowerId, latitude = 45.8, longitude = 15.999)

        val result = HotspotMarkerHitSelector.select(45.8, 16.0, listOf(east, west), zoom = 12.0)

        assertEquals(lowerId, result?.id)
    }
}
