package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Independent haversine, deliberately duplicated rather than made internal-visible — used only to
 * compute an exact "click is precisely on the boundary" radius for one test. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = HotspotGeometry.EARTH_RADIUS_METERS
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

class HotspotHitSelectorTest {

    private fun hotspot(
        id: UUID = UUID.randomUUID(),
        latitude: Double = 45.8,
        longitude: Double = 16.0,
        radiusMeters: Double = 100.0
    ) = Hotspot(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        confidence = 84,
        deviceCount = 3,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    @Test
    fun `a click inside one hotspot selects it`() {
        val target = hotspot(radiusMeters = 500.0)

        val result = HotspotHitSelector.select(45.8, 16.0, listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `a click outside all hotspots selects none`() {
        val farAway = hotspot(latitude = 0.0, longitude = 0.0, radiusMeters = 50.0)

        val result = HotspotHitSelector.select(45.8, 16.0, listOf(farAway))

        assertNull(result)
    }

    @Test
    fun `overlapping hotspots choose the nearest centroid`() {
        // Both centered near the click, both large enough to cover it; the closer one must win.
        val near = hotspot(latitude = 45.8001, longitude = 16.0, radiusMeters = 5000.0)
        val far = hotspot(latitude = 45.9, longitude = 16.0, radiusMeters = 20_000.0)

        val result = HotspotHitSelector.select(45.8, 16.0, listOf(far, near))

        assertEquals(near, result)
    }

    @Test
    fun `an exact distance tie uses deterministic UUID ordering`() {
        val lowerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val higherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        // Symmetric around the click point at the same distance, both covering it.
        val east = hotspot(id = higherId, latitude = 45.8, longitude = 16.001, radiusMeters = 500.0)
        val west = hotspot(id = lowerId, latitude = 45.8, longitude = 15.999, radiusMeters = 500.0)

        val result = HotspotHitSelector.select(45.8, 16.0, listOf(east, west))

        assertEquals(lowerId, result?.id)
    }

    @Test
    fun `a click exactly on the boundary is considered inside`() {
        val centerLat = 45.8
        val centerLon = 16.0
        val clickLat = 45.8
        val clickLon = 16.001
        val exactDistance = haversineMeters(centerLat, centerLon, clickLat, clickLon)
        val target = hotspot(latitude = centerLat, longitude = centerLon, radiusMeters = exactDistance)

        val result = HotspotHitSelector.select(clickLat, clickLon, listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `an empty hotspot list selects none`() {
        assertNull(HotspotHitSelector.select(45.8, 16.0, emptyList()))
    }

    // --- item 12: zero/tiny radius uses the same MIN_EFFECTIVE_RADIUS_METERS floor as rendering ---

    @Test
    fun `a click near the centroid of a zero-radius hotspot still selects it`() {
        val target = hotspot(radiusMeters = 0.0)
        val nearCentroidLat = 45.8 // a click well within MIN_EFFECTIVE_RADIUS_METERS, not exactly on it

        val result = HotspotHitSelector.select(nearCentroidLat, 16.0002, listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `a click well outside the minimum hit target of a zero-radius hotspot selects none`() {
        val target = hotspot(radiusMeters = 0.0)
        // Far beyond MIN_EFFECTIVE_RADIUS_METERS (25 m) — roughly 7.8 km away at this latitude.
        val result = HotspotHitSelector.select(45.8, 16.1, listOf(target))

        assertNull(result)
    }

    @Test
    fun `a click within the minimum hit target but beyond a tiny positive radius still selects it`() {
        val tinyRadius = 1.0
        check(tinyRadius < HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS)
        val target = hotspot(radiusMeters = tinyRadius)

        val result = HotspotHitSelector.select(45.8, 16.0002, listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `a click just outside a normal (above-floor) radius is not pulled in by the minimum hit target`() {
        // radiusMeters already exceeds MIN_EFFECTIVE_RADIUS_METERS, so the floor must have no effect
        // — a click just past the hotspot's own real radius must still miss.
        val radius = HotspotGeometry.MIN_EFFECTIVE_RADIUS_METERS + 10.0
        val target = hotspot(latitude = 0.0, longitude = 0.0, radiusMeters = radius)
        val justOutsideLongitude = Math.toDegrees((radius + 5.0) / HotspotGeometry.EARTH_RADIUS_METERS)

        val result = HotspotHitSelector.select(0.0, justOutsideLongitude, listOf(target))

        assertNull(result)
    }
}
