package hr.sonicpulse.app.ui.map

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotBoundsTest {

    private fun polygon(vararg points: Pair<Double, Double>) = HotspotPolygon(
        hotspotId = UUID.randomUUID(),
        deviceCount = 3,
        ring = points.map { (lon, lat) -> GeoPosition(longitude = lon, latitude = lat) }
    )

    @Test
    fun `ordinary non-crossing coordinates preserve expected bounds`() {
        val span = HotspotBounds.compute(listOf(polygon(10.0 to 40.0, 20.0 to 50.0)))!!

        assertEquals(10.0, span.westLongitude, 0.0)
        assertEquals(20.0, span.eastLongitude, 0.0)
        assertEquals(40.0, span.southLatitude, 0.0)
        assertEquals(50.0, span.northLatitude, 0.0)
        assertTrue(!span.crossesAntimeridian)
    }

    @Test
    fun `a small polygon crossing plus180 and minus180 produces a narrow span`() {
        val span = HotspotBounds.compute(listOf(polygon(179.9 to 45.0, -179.9 to 45.0)))!!

        assertTrue(span.crossesAntimeridian)
        assertEquals(0.2, span.longitudeSpanDegrees, 1e-9)
    }

    @Test
    fun `the circular center remains near the anti-meridian for a crossing dataset`() {
        val span = HotspotBounds.compute(listOf(polygon(179.9 to 45.0, -179.9 to 45.0)))!!

        assertTrue(span.centerLongitude >= 179.0 || span.centerLongitude <= -179.0)
    }

    @Test
    fun `input ordering does not affect the result`() {
        val forward = HotspotBounds.compute(listOf(polygon(179.9 to 45.0, -179.9 to 45.0, 179.95 to 45.0)))!!
        val reversed = HotspotBounds.compute(listOf(polygon(179.95 to 45.0, -179.9 to 45.0, 179.9 to 45.0)))!!

        assertEquals(forward, reversed)
    }

    @Test
    fun `a single point produces a deterministic zero-span center`() {
        val span = HotspotBounds.compute(listOf(polygon(16.0 to 45.8)))!!

        assertEquals(16.0, span.westLongitude, 0.0)
        assertEquals(16.0, span.eastLongitude, 0.0)
        assertEquals(16.0, span.centerLongitude, 0.0)
        assertEquals(0.0, span.longitudeSpanDegrees, 0.0)
        assertTrue(!span.crossesAntimeridian)
    }

    @Test
    fun `an empty polygon list returns no span`() {
        assertNull(HotspotBounds.compute(emptyList()))
    }

    @Test
    fun `non-finite points are excluded and never reach the result`() {
        val withNaN = HotspotPolygon(
            hotspotId = UUID.randomUUID(),
            deviceCount = 3,
            ring = listOf(GeoPosition(Double.NaN, Double.NaN), GeoPosition(16.0, 45.8))
        )

        val span = HotspotBounds.compute(listOf(withNaN))!!

        assertEquals(16.0, span.westLongitude, 0.0)
        assertEquals(45.8, span.southLatitude, 0.0)
    }

    @Test
    fun `entirely non-finite input returns no span`() {
        val allNaN = HotspotPolygon(
            hotspotId = UUID.randomUUID(),
            deviceCount = 3,
            ring = listOf(GeoPosition(Double.NaN, Double.NaN))
        )

        assertNull(HotspotBounds.compute(listOf(allNaN)))
    }

    @Test
    fun `multiple polygons all contribute to the final span`() {
        val a = polygon(0.0 to 10.0, 5.0 to 12.0)
        val b = polygon(10.0 to 20.0, 15.0 to 22.0)

        val span = HotspotBounds.compute(listOf(a, b))!!

        assertEquals(0.0, span.westLongitude, 0.0)
        assertEquals(15.0, span.eastLongitude, 0.0)
        assertEquals(10.0, span.southLatitude, 0.0)
        assertEquals(22.0, span.northLatitude, 0.0)
    }
}
