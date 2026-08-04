package hr.sonicpulse.app.ui.map

/**
 * The minimal geographic span containing a set of points. Latitude never wraps, so
 * [southLatitude]/[northLatitude] are ordinary min/max. Longitude *does* wrap at ±180°, so
 * [westLongitude]/[eastLongitude]/[centerLongitude]/[longitudeSpanDegrees] come from the minimal
 * *circular* interval containing every longitude (§9) — a plain min/max longitude would turn a
 * small hotspot cluster near the anti-meridian into an almost-worldwide span. When
 * [crossesAntimeridian] is true, `westLongitude > eastLongitude` in raw terms and the pair should
 * not be fed into an ordinary (non-wrapping) bounding-box camera fit — use [centerLongitude] and
 * [longitudeSpanDegrees] instead (see [HotspotCamera]).
 */
data class GeoSpan(
    val southLatitude: Double,
    val northLatitude: Double,
    val westLongitude: Double,
    val eastLongitude: Double,
    val crossesAntimeridian: Boolean,
    val centerLongitude: Double,
    val longitudeSpanDegrees: Double
)

object HotspotBounds {

    /** `null` when there is nothing finite to compute a span from. */
    fun compute(polygons: List<HotspotPolygon>): GeoSpan? {
        val points = polygons.asSequence()
            .flatMap { it.ring }
            .filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .toList()
        if (points.isEmpty()) return null

        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val longitudes = points.map { it.longitude }

        return minimalCircularLongitudeSpan(longitudes)?.let { lon ->
            GeoSpan(
                southLatitude = south,
                northLatitude = north,
                westLongitude = lon.west,
                eastLongitude = lon.east,
                crossesAntimeridian = lon.crossesAntimeridian,
                centerLongitude = lon.center,
                longitudeSpanDegrees = lon.spanDegrees
            )
        }
    }

    private data class LongitudeSpan(
        val west: Double,
        val east: Double,
        val crossesAntimeridian: Boolean,
        val center: Double,
        val spanDegrees: Double
    )

    /**
     * The minimal arc on the longitude circle (period 360°, values in `-180.0..180.0`) containing
     * every input value — the standard "largest gap" construction: sort the values, find the
     * largest empty gap around the circle (including the wraparound gap from the highest value
     * back to the lowest), and the minimal enclosing arc is the complement of that gap.
     *
     * If the largest gap *is* the wraparound gap, the minimal arc never crosses ±180° and this
     * degenerates to ordinary `west = min, east = max`. Otherwise the minimal arc crosses the
     * anti-meridian, which [crossesAntimeridian] signals explicitly.
     */
    private fun minimalCircularLongitudeSpan(longitudes: List<Double>): LongitudeSpan? {
        if (longitudes.isEmpty()) return null
        if (longitudes.size == 1) {
            val lon = longitudes.single()
            return LongitudeSpan(west = lon, east = lon, crossesAntimeridian = false, center = lon, spanDegrees = 0.0)
        }

        val sorted = longitudes.sorted()
        val n = sorted.size

        var largestGap = (sorted[0] + 360.0) - sorted[n - 1] // the wraparound gap, index n-1
        var largestGapIndex = n - 1
        for (i in 0 until n - 1) {
            val gap = sorted[i + 1] - sorted[i]
            if (gap > largestGap) {
                largestGap = gap
                largestGapIndex = i
            }
        }

        return if (largestGapIndex == n - 1) {
            // The emptiest stretch of the circle is the seam itself — the minimal arc is the
            // ordinary, non-wrapping [min, max].
            val west = sorted[0]
            val east = sorted[n - 1]
            LongitudeSpan(west, east, crossesAntimeridian = false, center = (west + east) / 2.0, spanDegrees = east - west)
        } else {
            val west = sorted[largestGapIndex + 1]
            val east = sorted[largestGapIndex]
            val span = 360.0 - largestGap
            var center = west + span / 2.0
            if (center > 180.0) center -= 360.0
            if (center < -180.0) center += 360.0
            LongitudeSpan(west, east, crossesAntimeridian = true, center = center, spanDegrees = span)
        }
    }
}
