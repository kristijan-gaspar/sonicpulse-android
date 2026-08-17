package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotFromClickedFeaturesTest {

    private fun hotspot(id: UUID = UUID.randomUUID()) = Hotspot(
        id = id,
        latitude = 45.8,
        longitude = 16.0,
        radiusMeters = 16.0,
        deviceCount = 3,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    private fun propertiesFor(hotspotId: UUID): JsonObject = buildJsonObject {
        put("hotspotId", hotspotId.toString())
    }

    @Test
    fun `resolves the hotspot matching the clicked feature's hotspotId`() {
        val target = hotspot()
        val other = hotspot()

        val result = hotspotFromClickedFeatures(listOf(propertiesFor(target.id)), listOf(target, other))

        assertEquals(target, result)
    }

    @Test
    fun `no clicked features returns null`() {
        assertNull(hotspotFromClickedFeatures(emptyList(), listOf(hotspot())))
    }

    @Test
    fun `a feature with null properties is ignored`() {
        val target = hotspot()

        val result = hotspotFromClickedFeatures(listOf(null, propertiesFor(target.id)), listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `a feature missing the hotspotId property is ignored`() {
        val target = hotspot()
        val malformed = buildJsonObject { put("somethingElse", "x") }

        val result = hotspotFromClickedFeatures(listOf(malformed), listOf(target))

        assertNull(result)
    }

    @Test
    fun `a hotspotId not present in the current hotspot list returns null`() {
        val staleId = UUID.randomUUID()

        val result = hotspotFromClickedFeatures(listOf(propertiesFor(staleId)), listOf(hotspot()))

        assertNull(result)
    }

    @Test
    fun `a malformed hotspotId value is ignored rather than throwing`() {
        val target = hotspot()
        val malformed = buildJsonObject { put("hotspotId", "not-a-uuid") }

        val result = hotspotFromClickedFeatures(listOf(malformed, propertiesFor(target.id)), listOf(target))

        assertEquals(target, result)
    }

    @Test
    fun `multiple candidate features break deterministically on the lowest hotspot id`() {
        val lowerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val higherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val lower = hotspot(id = lowerId)
        val higher = hotspot(id = higherId)

        val result = hotspotFromClickedFeatures(
            listOf(propertiesFor(higherId), propertiesFor(lowerId)),
            listOf(higher, lower)
        )

        assertEquals(lowerId, result?.id)
    }
}
