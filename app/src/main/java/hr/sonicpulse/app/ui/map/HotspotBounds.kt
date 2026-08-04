package hr.sonicpulse.app.ui.map

import org.maplibre.spatialk.geojson.BoundingBox

/** Computes a camera bounding box from the generated polygon coordinates (not just centroids), so
 * the camera fit actually includes each hotspot's full radius, not just its center point. `null`
 * when there is nothing to fit. */
object HotspotBounds {
    fun compute(polygons: List<HotspotPolygon>): BoundingBox? {
        var west = Double.POSITIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY

        polygons.forEach { polygon ->
            polygon.ring.forEach { point ->
                if (point.longitude < west) west = point.longitude
                if (point.longitude > east) east = point.longitude
                if (point.latitude < south) south = point.latitude
                if (point.latitude > north) north = point.latitude
            }
        }

        if (!west.isFinite() || !south.isFinite() || !east.isFinite() || !north.isFinite()) {
            return null
        }
        return BoundingBox(west = west, south = south, east = east, north = north)
    }
}
