package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.datastore.FakeInstallationIdRepository
import hr.sonicpulse.app.data.remote.DetectionHistoryItemDto
import hr.sonicpulse.app.data.remote.DetectionHistoryPageDto
import hr.sonicpulse.app.data.remote.FakeDetectionApi
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultDetectionsRepositoryTest {

    private val itemDto = DetectionHistoryItemDto(
        id = "22222222-2222-2222-2222-222222222222",
        sequenceNumber = 123L,
        deviceId = "11111111-1111-1111-1111-111111111111",
        peakDbfs = -8.5,
        latitude = 45.8,
        longitude = 16.0,
        gpsAccuracy = 8.0,
        receivedAtUtc = "2026-08-03T10:00:00Z",
        peakTimeClient = "2026-08-03T09:59:59Z",
        hotspotId = "33333333-3333-3333-3333-333333333333"
    )

    @Test
    fun `resolves the installation id and passes it, the cursor and the limit to the API unchanged`() = runTest {
        val api = FakeDetectionApi()
        val installationIdRepository = FakeInstallationIdRepository("11111111-1111-1111-1111-111111111111")
        val repository = DefaultDetectionsRepository(api, installationIdRepository)

        repository.getDetectionsPage(cursor = 42L, limit = 25)

        val (deviceId, cursor, limit) = api.historyRequests.single()
        assertEquals("11111111-1111-1111-1111-111111111111", deviceId)
        assertEquals(42L, cursor)
        assertEquals(25, limit)
    }

    @Test
    fun `the initial call passes cursor null through unchanged`() = runTest {
        val api = FakeDetectionApi()
        val repository = DefaultDetectionsRepository(api, FakeInstallationIdRepository())

        repository.getDetectionsPage(cursor = null, limit = 50)

        assertNull(api.historyRequests.single().second)
    }

    @Test
    fun `maps the DTO page to the domain page, preserving nextCursor`() = runTest {
        val api = FakeDetectionApi().apply {
            historyResponse = DetectionHistoryPageDto(items = listOf(itemDto), nextCursor = 123L)
        }
        val repository = DefaultDetectionsRepository(api, FakeInstallationIdRepository())

        val page = repository.getDetectionsPage(cursor = null, limit = 50)

        assertEquals(1, page.items.size)
        assertEquals(123L, page.items.single().sequenceNumber)
        assertEquals(123L, page.nextCursor)
    }

    @Test
    fun `nextCursor null maps through as null`() = runTest {
        val api = FakeDetectionApi().apply {
            historyResponse = DetectionHistoryPageDto(items = emptyList(), nextCursor = null)
        }
        val repository = DefaultDetectionsRepository(api, FakeInstallationIdRepository())

        val page = repository.getDetectionsPage(cursor = null, limit = 50)

        assertNull(page.nextCursor)
    }

    @Test
    fun `an API failure propagates rather than being swallowed`() {
        val api = FakeDetectionApi().apply { throwOnGetHistory = IOException("timeout") }
        val repository = DefaultDetectionsRepository(api, FakeInstallationIdRepository())

        assertThrows(IOException::class.java) {
            runBlocking { repository.getDetectionsPage(cursor = null, limit = 50) }
        }
    }

    @Test
    fun `an installation-id resolution failure propagates`() {
        val installationIdRepository = FakeInstallationIdRepository().apply {
            throwOnGetOrCreate = IOException("corrupted preferences file")
        }
        val repository = DefaultDetectionsRepository(FakeDetectionApi(), installationIdRepository)

        assertThrows(IOException::class.java) {
            runBlocking { repository.getDetectionsPage(cursor = null, limit = 50) }
        }
    }
}
