package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedHotspotFromTest {

    private fun hotspot(
        id: UUID = UUID.randomUUID(),
        confidence: Int = 84,
        deviceCount: Int = 3
    ) = Hotspot(
        id = id,
        latitude = 45.8,
        longitude = 16.0,
        radiusMeters = 100.0,
        confidence = confidence,
        deviceCount = deviceCount,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    @Test
    fun `no selection returns null regardless of the loaded list`() {
        assertNull(selectedHotspotFrom(listOf(hotspot()), selectedId = null))
    }

    @Test
    fun `an unchanged hotspot remains selected`() {
        val target = hotspot()

        val result = selectedHotspotFrom(listOf(target), selectedId = target.id)

        assertEquals(target, result)
    }

    @Test
    fun `a selected hotspot updated by a refresh reflects the new data, not the stale snapshot`() {
        val id = UUID.randomUUID()
        val updated = hotspot(id = id, confidence = 99, deviceCount = 7)
        val staleList = listOf(hotspot(id = id, confidence = 50, deviceCount = 2))
        check(selectedHotspotFrom(staleList, id) != updated) { "test setup invalid" }

        val result = selectedHotspotFrom(listOf(updated), selectedId = id)

        assertEquals(updated, result)
        assertEquals(99, result?.confidence)
    }

    @Test
    fun `a selected hotspot no longer in the list (removed by a refresh) returns null, closing the sheet`() {
        val removedId = UUID.randomUUID()
        val stillPresent = hotspot()

        val result = selectedHotspotFrom(listOf(stillPresent), selectedId = removedId)

        assertNull(result)
    }

    @Test
    fun `a selected hotspot filtered out by a range change returns null, closing the sheet`() {
        // Modeled the same as "removed": the caller only ever passes the currently loaded/committed
        // list, so a hotspot outside the newly committed range is simply absent from it.
        val filteredOutId = UUID.randomUUID()
        val remainingAfterFilter = listOf(hotspot(), hotspot())

        val result = selectedHotspotFrom(remainingAfterFilter, selectedId = filteredOutId)

        assertNull(result)
    }

    @Test
    fun `an empty hotspot list with a selection returns null`() {
        assertNull(selectedHotspotFrom(emptyList(), selectedId = UUID.randomUUID()))
    }
}
