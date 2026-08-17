package hr.sonicpulse.app.data.remote

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HotspotMapperTest {

    private fun dto(
        id: String = "22222222-2222-2222-2222-222222222222",
        latitude: Double = 45.8,
        longitude: Double = 16.0,
        radiusMeters: Double = 120.5,
        deviceCount: Int = 3,
        firstReceivedAtUtc: String = "2026-08-03T10:00:00Z",
        lastReceivedAtUtc: String = "2026-08-03T10:00:12Z"
    ) = HotspotDto(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        deviceCount = deviceCount,
        firstReceivedAtUtc = firstReceivedAtUtc,
        lastReceivedAtUtc = lastReceivedAtUtc
    )

    @Test
    fun `maps a valid UUID and both timestamps`() {
        val hotspot = HotspotMapper.toDomain(dto())

        assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), hotspot.id)
        assertEquals(Instant.parse("2026-08-03T10:00:00Z"), hotspot.firstReceivedAtUtc)
        assertEquals(Instant.parse("2026-08-03T10:00:12Z"), hotspot.lastReceivedAtUtc)
    }

    @Test
    fun `maps latitude and longitude unchanged`() {
        val hotspot = HotspotMapper.toDomain(dto(latitude = -33.5, longitude = 151.2))

        assertEquals(-33.5, hotspot.latitude, 0.0)
        assertEquals(151.2, hotspot.longitude, 0.0)
    }

    @Test
    fun `maps radiusMeters unchanged`() {
        val hotspot = HotspotMapper.toDomain(dto(radiusMeters = 0.0))

        assertEquals(0.0, hotspot.radiusMeters, 0.0)
    }

    @Test
    fun `maps deviceCount unchanged`() {
        val hotspot = HotspotMapper.toDomain(dto(deviceCount = 7))

        assertEquals(7, hotspot.deviceCount)
    }

    @Test
    fun `a malformed UUID fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(id = "not-a-uuid"))
        }
    }

    @Test
    fun `a malformed first timestamp fails`() {
        assertThrows(Exception::class.java) {
            HotspotMapper.toDomain(dto(firstReceivedAtUtc = "not-a-timestamp"))
        }
    }

    @Test
    fun `a malformed last timestamp fails`() {
        assertThrows(Exception::class.java) {
            HotspotMapper.toDomain(dto(lastReceivedAtUtc = "not-a-timestamp"))
        }
    }

    @Test
    fun `latitude above 90 fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(latitude = 90.1))
        }
    }

    @Test
    fun `latitude below negative 90 fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(latitude = -90.1))
        }
    }

    @Test
    fun `longitude above 180 fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(longitude = 180.1))
        }
    }

    @Test
    fun `longitude below negative 180 fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(longitude = -180.1))
        }
    }

    @Test
    fun `a non-finite latitude fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(latitude = Double.NaN))
        }
    }

    @Test
    fun `negative radius fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(radiusMeters = -0.1))
        }
    }

    @Test
    fun `device count below 2 fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(dto(deviceCount = 1))
        }
    }

    @Test
    fun `first timestamp after last timestamp fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotMapper.toDomain(
                dto(firstReceivedAtUtc = "2026-08-03T10:00:13Z", lastReceivedAtUtc = "2026-08-03T10:00:12Z")
            )
        }
    }

    @Test
    fun `first timestamp equal to last timestamp succeeds`() {
        val hotspot = HotspotMapper.toDomain(
            dto(firstReceivedAtUtc = "2026-08-03T10:00:00Z", lastReceivedAtUtc = "2026-08-03T10:00:00Z")
        )

        assertEquals(hotspot.firstReceivedAtUtc, hotspot.lastReceivedAtUtc)
    }
}
