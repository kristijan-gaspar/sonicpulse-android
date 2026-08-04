package hr.sonicpulse.app.ui.map

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
}
