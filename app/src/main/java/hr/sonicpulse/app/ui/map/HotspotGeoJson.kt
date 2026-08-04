package hr.sonicpulse.app.ui.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serializes [HotspotPolygon]s into a GeoJSON `FeatureCollection` string for
 * [org.maplibre.compose.sources.GeoJsonData.JsonString] — the only point where this feature's pure
 * geometry model touches an actual text format. Kept separate from [HotspotGeometry] so the
 * geometry math itself stays free of any serialization concern. */
object HotspotGeoJson {

    fun featureCollection(polygons: List<HotspotPolygon>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray { polygons.forEach { add(feature(it)) } })
    }.toString()

    private fun feature(polygon: HotspotPolygon): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", buildJsonObject {
            put("hotspotId", polygon.hotspotId.toString())
            put("deviceCount", polygon.deviceCount)
            put("confidence", polygon.confidence)
        })
        put("geometry", buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray { add(ring(polygon.ring)) })
        })
    }

    private fun ring(points: List<GeoPosition>): JsonArray = buildJsonArray {
        points.forEach { point ->
            add(buildJsonArray {
                add(JsonPrimitive(point.longitude))
                add(JsonPrimitive(point.latitude))
            })
        }
    }
}
