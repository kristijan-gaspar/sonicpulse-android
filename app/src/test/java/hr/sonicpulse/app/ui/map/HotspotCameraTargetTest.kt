package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotCameraTargetTest {

    private fun hotspot(
        latitude: Double = 45.8,
        longitude: Double = 16.0,
        radiusMeters: Double = 200.0,
        deviceCount: Int = 3
    ) = Hotspot(
        id = UUID.randomUUID(),
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        confidence = 84,
        deviceCount = deviceCount,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    @Test
    fun `no hotspots returns KeepCurrent`() {
        assertEquals(HotspotCameraTarget.KeepCurrent, HotspotCamera.targetFor(emptyList()))
    }

    @Test
    fun `a single zero-radius hotspot returns a Center target at its own coordinates`() {
        val target = HotspotCamera.targetFor(listOf(hotspot(latitude = 45.8, longitude = 16.0, radiusMeters = 0.0)))

        val center = target as HotspotCameraTarget.Center
        assertEquals(45.8, center.latitude, 0.0)
        assertEquals(16.0, center.longitude, 0.0)
        assertEquals(HotspotCamera.SINGLE_POINT_ZOOM, center.zoom, 0.0)
    }

    @Test
    fun `a single normal-radius hotspot returns Bounds covering its full radius`() {
        val target = HotspotCamera.targetFor(listOf(hotspot(radiusMeters = 500.0)))

        val bounds = target as HotspotCameraTarget.Bounds
        assertTrue(bounds.boundingBox.west < 16.0)
        assertTrue(bounds.boundingBox.east > 16.0)
        assertTrue(bounds.boundingBox.south < 45.8)
        assertTrue(bounds.boundingBox.north > 45.8)
    }

    @Test
    fun `multiple ordinary hotspots return Bounds including every coordinate`() {
        val a = hotspot(latitude = 45.0, longitude = 15.0, radiusMeters = 100.0)
        val b = hotspot(latitude = 46.0, longitude = 17.0, radiusMeters = 100.0)

        val target = HotspotCamera.targetFor(listOf(a, b))

        val bounds = target as HotspotCameraTarget.Bounds
        assertTrue(bounds.boundingBox.west < 15.0)
        assertTrue(bounds.boundingBox.east > 17.0)
        assertTrue(bounds.boundingBox.south < 45.0)
        assertTrue(bounds.boundingBox.north > 46.0)
    }

    @Test
    fun `hotspots crossing the anti-meridian return a Center target near it`() {
        val a = hotspot(latitude = 45.0, longitude = 179.95, radiusMeters = 10.0)
        val b = hotspot(latitude = 45.0, longitude = -179.95, radiusMeters = 10.0)

        val target = HotspotCamera.targetFor(listOf(a, b))

        val center = target as HotspotCameraTarget.Center
        assertTrue(center.longitude >= 179.0 || center.longitude <= -179.0)
        // A narrow real-world span must not be zoomed all the way out to fit the whole globe.
        assertTrue(center.zoom > 5.0)
    }

    @Test
    fun `non-finite hotspot coordinates never reach the camera`() {
        val invalid = hotspot(latitude = Double.NaN, longitude = Double.NaN)

        assertEquals(HotspotCameraTarget.KeepCurrent, HotspotCamera.targetFor(listOf(invalid)))
    }
}
