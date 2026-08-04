package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.R

/** The 3 ranges the client ever sends — the backend clamps `sinceHours` to 1..168, but nothing
 * finer-grained than these is ever exposed in the UI. */
enum class HotspotTimeRange(val hours: Int, val labelRes: Int) {
    Last24Hours(24, R.string.range_last_24_hours),
    Last3Days(72, R.string.range_last_3_days),
    Last7Days(168, R.string.range_last_7_days)
}
