package hr.sonicpulse.app.ui.map

import java.time.Duration
import java.time.Instant

/** The bucket the bottom sheet's time-span text is drawn from — resolved to a localized string in
 * the Composable via the matching `R.string.duration_*` template, e.g. "12 s", "2 min 15 s",
 * "1 h 4 min". Pure and JVM-testable, mirroring [hr.sonicpulse.app.ui.detections.SectionHeaderDate]. */
sealed interface HotspotTimeSpan {
    data class Seconds(val seconds: Long) : HotspotTimeSpan
    data class MinutesSeconds(val minutes: Long, val seconds: Long) : HotspotTimeSpan
    data class HoursMinutes(val hours: Long, val minutes: Long) : HotspotTimeSpan
}

/** `lastReceivedAtUtc - firstReceivedAtUtc`, per §12 of the plan. Negative durations (which the
 * backend guarantees can't happen — `firstReceivedAtUtc <= lastReceivedAtUtc` — but a defensive
 * floor costs nothing) are clamped to zero. */
fun hotspotTimeSpanFor(firstReceivedAtUtc: Instant, lastReceivedAtUtc: Instant): HotspotTimeSpan {
    val totalSeconds = Duration.between(firstReceivedAtUtc, lastReceivedAtUtc).seconds.coerceAtLeast(0)
    return when {
        totalSeconds < 60 -> HotspotTimeSpan.Seconds(totalSeconds)
        totalSeconds < 3600 -> HotspotTimeSpan.MinutesSeconds(totalSeconds / 60, totalSeconds % 60)
        else -> HotspotTimeSpan.HoursMinutes(totalSeconds / 3600, (totalSeconds % 3600) / 60)
    }
}

/** [Hotspot.confidence][hr.sonicpulse.app.domain.model.Hotspot.confidence] is a heuristic 0–100
 * score computed server-side from device count and temporal compactness — not a probability — so
 * it is rendered as "X / 100", never as a percentage. Locale-invariant (plain ASCII digits and a
 * slash), so unlike [hotspotTimeSpanFor]'s buckets this needs no separate localized template. */
fun confidenceScoreText(confidence: Int): String = "$confidence / 100"
