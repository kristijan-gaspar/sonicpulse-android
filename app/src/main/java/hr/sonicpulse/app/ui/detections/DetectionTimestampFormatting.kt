package hr.sonicpulse.app.ui.detections

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure, JVM-testable date/time formatting for the Detections screen — [zone]/[locale] are always
 * parameters (never read from `ZoneId.systemDefault()`/`Locale.getDefault()` internally) so tests
 * can assert against a fixed zone/locale instead of depending on the machine's own settings.
 * Everything here is derived from `receivedAtUtc` (backend-authoritative), never `peakTimeClient`.
 */

private fun listTimeFormatter(locale: Locale, zone: ZoneId): DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", locale).withZone(zone)

/** List row — short local time only, e.g. "14:32:07". */
internal fun listTimestampTextFor(instant: Instant, zone: ZoneId, locale: Locale): String =
    listTimeFormatter(locale, zone).format(instant)

/** Detail bottom sheet — localized full date, plus the same 24-hour time as the list (not a
 * locale-driven 12-hour clock, so it never disagrees with the list row for the same detection). */
internal fun detailTimestampTextFor(instant: Instant, zone: ZoneId, locale: Locale): String {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(instant.atZone(zone))
    val time = listTimeFormatter(locale, zone).format(instant)
    return "$date $time"
}

/** Which of the two sticky-header variants applies, and its already-localized date text — kept
 * pure so the "Today — %s" template substitution (a string resource) is the only Android-specific
 * step left, done in the Composable. */
sealed interface SectionHeaderDate {
    data class Today(val shortDate: String) : SectionHeaderDate
    data class OtherDate(val longDate: String) : SectionHeaderDate
}

internal fun sectionHeaderDateFor(date: LocalDate, today: LocalDate, locale: Locale): SectionHeaderDate =
    if (date == today) {
        SectionHeaderDate.Today(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(date))
    } else {
        SectionHeaderDate.OtherDate(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date))
    }
