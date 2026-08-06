package hr.sonicpulse.app.ui.map

import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent haversine distance, deliberately not shared with production code, so a bug in one
 * couldn't hide the same bug in the other. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = HotspotGeometry.EARTH_RADIUS_METERS
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

class HotspotGeometryTest {

    private val hotspotId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `the ring has SEGMENTS plus 1 vertices`() {
        val polygon = HotspotGeometry.polygonFor(
            hotspotId = hotspotId,
            centerLatitude = 45.8,
            centerLongitude = 16.0,
            radiusMeters = 200.0,
            deviceCount = 3,
            confidence = 84
        )

        assertEquals(HotspotGeometry.SEGMENTS + 1, polygon.ring.size)
    }

    @Test
    fun `the final coordinate equals the first coordinate`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, 200.0, 3, 84)

        assertEquals(polygon.ring.first(), polygon.ring.last())
    }

    @Test
    fun `every generated coordinate is finite and within valid ranges`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, 500.0, 3, 84)

        polygon.ring.forEach { point ->
            assertTrue(point.latitude.isFinite())
            assertTrue(point.longitude.isFinite())
            assertTrue(point.latitude in -90.0..90.0)
            assertTrue(point.longitude in -180.0..180.0)
        }
    }

    @Test
    fun `each generated point is approximately radiusMeters from the centroid`() {
        val radius = 300.0
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, radius, 3, 84)

        polygon.ring.dropLast(1).forEach { point ->
            val distance = haversineMeters(45.8, 16.0, point.latitude, point.longitude)
            assertTrue("expected ~$radius m, was $distance m", Math.abs(distance - radius) < 1.0)
        }
    }

    @Test
    fun `GeoJSON coordinate order is longitude then latitude`() {
        // A point due east of the centroid at the equator increases longitude, latitude unchanged.
        val polygon = HotspotGeometry.polygonFor(hotspotId, 0.0, 0.0, 1000.0, 3, 84)

        val east = polygon.ring[HotspotGeometry.SEGMENTS / 4] // bearing 90 degrees
        assertTrue(east.longitude > 0.0)
        assertTrue(Math.abs(east.latitude) < 0.01)
    }

    @Test
    fun `zero radius renders at the MIN_EFFECTIVE_RADIUS_METERS floor, not a degenerate point`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, 0.0, 3, 84)

        polygon.ring.dropLast(1).forEach { point ->
            val distance = haversineMeters(45.8, 16.0, point.latitude, point.longitude)
            assertTrue(
                "expected ~${HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS} m, was $distance m",
                Math.abs(distance - HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS) < 1.0
            )
        }
    }

    @Test
    fun `a very small positive radius below the floor also renders at MIN_EFFECTIVE_RADIUS_METERS`() {
        val tinyRadius = 1.0
        check(tinyRadius < HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS)
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, tinyRadius, 3, 84)

        polygon.ring.dropLast(1).forEach { point ->
            val distance = haversineMeters(45.8, 16.0, point.latitude, point.longitude)
            assertTrue(
                "expected ~${HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS} m, was $distance m",
                Math.abs(distance - HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS) < 1.0
            )
        }
    }

    @Test
    fun `a radius already above the floor is rendered at its own real value, unmodified`() {
        val radius = HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS + 500.0
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, radius, 3, 84)

        polygon.ring.dropLast(1).forEach { point ->
            val distance = haversineMeters(45.8, 16.0, point.latitude, point.longitude)
            assertTrue("expected ~$radius m, was $distance m", Math.abs(distance - radius) < 1.0)
        }
    }

    @Test
    fun `a large valid radius is rendered at its own real value, unmodified`() {
        val radius = 50_000.0
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, radius, 3, 84)

        polygon.ring.dropLast(1).forEach { point ->
            val distance = haversineMeters(45.8, 16.0, point.latitude, point.longitude)
            assertTrue("expected ~$radius m, was $distance m", Math.abs(distance - radius) < 50.0)
        }
    }

    @Test
    fun `coordinates near the anti-meridian remain normalized`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 0.0, 179.999, 5000.0, 3, 84)

        polygon.ring.forEach { point ->
            assertTrue(point.longitude in -180.0..180.0)
        }
        // At least one point must have wrapped past +180 into negative territory.
        assertTrue(polygon.ring.any { it.longitude < 0.0 })
    }

    @Test
    fun `high-latitude coordinates remain valid`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 89.9, 10.0, 5000.0, 3, 84)

        polygon.ring.forEach { point ->
            assertTrue(point.latitude.isFinite())
            assertTrue(point.latitude in -90.0..90.0)
            assertTrue(point.longitude.isFinite())
        }
    }

    @Test
    fun `the polygon carries hotspotId deviceCount and confidence properties`() {
        val polygon = HotspotGeometry.polygonFor(hotspotId, 45.8, 16.0, 200.0, 4, 91)

        assertEquals(hotspotId, polygon.hotspotId)
        assertEquals(4, polygon.deviceCount)
        assertEquals(91, polygon.confidence)
    }
}
