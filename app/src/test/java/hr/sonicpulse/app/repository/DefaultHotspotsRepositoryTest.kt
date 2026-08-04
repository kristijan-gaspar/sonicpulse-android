package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.remote.FakeHotspotApi
import hr.sonicpulse.app.data.remote.HotspotDto
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultHotspotsRepositoryTest {

    private fun dto(id: String, deviceCount: Int = 3) = HotspotDto(
        id = id,
        latitude = 45.8,
        longitude = 16.0,
        radiusMeters = 120.5,
        confidence = 84,
        deviceCount = deviceCount,
        firstReceivedAtUtc = "2026-08-03T10:00:00Z",
        lastReceivedAtUtc = "2026-08-03T10:00:12Z"
    )

    @Test
    fun `passes sinceHours to the API unchanged`() = runTest {
        val api = FakeHotspotApi()
        val repository = DefaultHotspotsRepository(api)

        repository.getHotspots(sinceHours = 72)

        assertEquals(listOf(72), api.requestedSinceHours)
    }

    @Test
    fun `maps every item`() = runTest {
        val api = FakeHotspotApi().apply {
            response = listOf(
                dto(id = "11111111-1111-1111-1111-111111111111"),
                dto(id = "22222222-2222-2222-2222-222222222222")
            )
        }
        val repository = DefaultHotspotsRepository(api)

        val hotspots = repository.getHotspots(sinceHours = 24)

        assertEquals(2, hotspots.size)
    }

    @Test
    fun `preserves backend order`() = runTest {
        val api = FakeHotspotApi().apply {
            response = listOf(
                dto(id = "11111111-1111-1111-1111-111111111111", deviceCount = 4),
                dto(id = "22222222-2222-2222-2222-222222222222", deviceCount = 2)
            )
        }
        val repository = DefaultHotspotsRepository(api)

        val hotspots = repository.getHotspots(sinceHours = 24)

        assertEquals(listOf(4, 2), hotspots.map { it.deviceCount })
    }

    @Test
    fun `an API failure propagates rather than being swallowed`() {
        val api = FakeHotspotApi().apply { throwOnGetHotspots = IOException("timeout") }
        val repository = DefaultHotspotsRepository(api)

        assertThrows(IOException::class.java) {
            runBlocking { repository.getHotspots(sinceHours = 24) }
        }
    }
}
