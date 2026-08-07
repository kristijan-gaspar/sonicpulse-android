package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.domain.model.Hotspot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serializes [HotspotPolygon]s (and, separately, hotspot centroids) into a GeoJSON
 * `FeatureCollection` string for [org.maplibre.compose.sources.GeoJsonData.JsonString] — the only
 * point where this feature's pure geometry model touches an actual text format. Kept separate from
 * [HotspotGeometry] so the geometry math itself stays free of any serialization concern. */
object HotspotGeoJson {

    fun featureCollection(polygons: List<HotspotPolygon>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray { polygons.forEach { add(feature(it)) } })
    }.toString()

    /** One Point feature per hotspot centroid, for the fixed screen-space marker layer (plan item
     * 2) — built straight from [Hotspot], not from [HotspotPolygon]'s already-computed ring: a
     * marker only needs the centroid, not 64 ring vertices. `label` carries the marker's
     * device-count text so the map style itself doesn't need a data-driven ">= 4" expression. */
    fun pointFeatureCollection(hotspots: List<Hotspot>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray { hotspots.forEach { add(pointFeature(it)) } })
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

    private fun pointFeature(hotspot: Hotspot): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("properties", buildJsonObject {
            put("hotspotId", hotspot.id.toString())
            put("deviceCount", hotspot.deviceCount)
            put("label", deviceCountLabel(hotspot.deviceCount))
        })
        put("geometry", buildJsonObject {
            put("type", "Point")
            put("coordinates", buildJsonArray {
                add(JsonPrimitive(hotspot.longitude))
                add(JsonPrimitive(hotspot.latitude))
            })
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

    /** The exact count for 2-3 devices, "4+" beyond that — the same bucket boundary as
     * [MapScreen]'s device-count color buckets and the map legend's own "4+" entry, reused (not
     * re-derived) by [hr.sonicpulse.app.ui.map.HotspotListBottomSheet] for its per-row badge. */
    internal fun deviceCountLabel(deviceCount: Int): String = if (deviceCount >= 4) "4+" else deviceCount.toString()
}
