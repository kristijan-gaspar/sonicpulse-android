package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotGeoJsonTest {

    @Test
    fun `an empty polygon list produces an empty FeatureCollection`() {
        val json = Json.parseToJsonElement(HotspotGeoJson.featureCollection(emptyList())).jsonObject

        assertEquals("FeatureCollection", json["type"]?.jsonPrimitive?.content)
        assertEquals(0, json["features"]?.jsonArray?.size)
    }

    @Test
    fun `a feature carries hotspotId deviceCount and confidence properties`() {
        val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val polygon = HotspotPolygon(
            hotspotId = id,
            deviceCount = 3,
            confidence = 84,
            ring = listOf(GeoPosition(16.0, 45.8), GeoPosition(16.0, 45.8))
        )

        val feature = Json.parseToJsonElement(HotspotGeoJson.featureCollection(listOf(polygon)))
            .jsonObject["features"]!!.jsonArray[0].jsonObject
        val properties = feature["properties"]!!.jsonObject

        assertEquals(id.toString(), properties["hotspotId"]?.jsonPrimitive?.content)
        assertEquals(3, properties["deviceCount"]?.jsonPrimitive?.int)
        assertEquals(84, properties["confidence"]?.jsonPrimitive?.int)
    }

    @Test
    fun `coordinates are emitted in longitude, latitude order`() {
        val polygon = HotspotPolygon(
            hotspotId = UUID.randomUUID(),
            deviceCount = 2,
            confidence = 70,
            ring = listOf(GeoPosition(longitude = 16.5, latitude = 45.1), GeoPosition(longitude = 16.5, latitude = 45.1))
        )

        val geometry = Json.parseToJsonElement(HotspotGeoJson.featureCollection(listOf(polygon)))
            .jsonObject["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject
        val firstCoordinate = geometry["coordinates"]!!.jsonArray[0].jsonArray[0].jsonArray

        assertEquals(16.5, firstCoordinate[0].jsonPrimitive.double, 0.0)
        assertEquals(45.1, firstCoordinate[1].jsonPrimitive.double, 0.0)
    }

    // --- pointFeatureCollection (fixed centroid marker layer, plan item 2) ---

    private fun hotspot(
        id: UUID = UUID.randomUUID(),
        latitude: Double = 45.8,
        longitude: Double = 16.0,
        deviceCount: Int = 3
    ) = Hotspot(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = 16.0,
        confidence = 84,
        deviceCount = deviceCount,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    @Test
    fun `an empty hotspot list produces an empty point FeatureCollection`() {
        val json = Json.parseToJsonElement(HotspotGeoJson.pointFeatureCollection(emptyList())).jsonObject

        assertEquals("FeatureCollection", json["type"]?.jsonPrimitive?.content)
        assertEquals(0, json["features"]?.jsonArray?.size)
    }

    @Test
    fun `a point feature is centered exactly at the hotspot centroid, in longitude-latitude order`() {
        val target = hotspot(latitude = 45.1, longitude = 16.5)

        val geometry = Json.parseToJsonElement(HotspotGeoJson.pointFeatureCollection(listOf(target)))
            .jsonObject["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject
        val coordinates = geometry["coordinates"]!!.jsonArray

        assertEquals("Point", geometry["type"]?.jsonPrimitive?.content)
        assertEquals(16.5, coordinates[0].jsonPrimitive.double, 0.0)
        assertEquals(45.1, coordinates[1].jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a point feature carries hotspotId deviceCount and a label property`() {
        val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val target = hotspot(id = id, deviceCount = 3)

        val properties = Json.parseToJsonElement(HotspotGeoJson.pointFeatureCollection(listOf(target)))
            .jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject

        assertEquals(id.toString(), properties["hotspotId"]?.jsonPrimitive?.content)
        assertEquals(3, properties["deviceCount"]?.jsonPrimitive?.int)
        assertEquals("3", properties["label"]?.jsonPrimitive?.content)
    }

    // --- deviceCountLabel ---

    @Test
    fun `deviceCountLabel is the exact count for 2 devices`() {
        assertEquals("2", HotspotGeoJson.deviceCountLabel(2))
    }

    @Test
    fun `deviceCountLabel is the exact count for 3 devices`() {
        assertEquals("3", HotspotGeoJson.deviceCountLabel(3))
    }

    @Test
    fun `deviceCountLabel is 4+ at the 4-device boundary`() {
        assertEquals("4+", HotspotGeoJson.deviceCountLabel(4))
    }

    @Test
    fun `deviceCountLabel stays 4+ well above the boundary`() {
        assertEquals("4+", HotspotGeoJson.deviceCountLabel(12))
    }
}
