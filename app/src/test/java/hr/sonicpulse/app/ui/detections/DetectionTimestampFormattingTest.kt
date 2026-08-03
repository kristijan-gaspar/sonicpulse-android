package hr.sonicpulse.app.ui.detections

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed zone/locale throughout — these must never depend on the machine's own settings or the
 * current date. */
class DetectionTimestampFormattingTest {

    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun `list timestamp is the short 24-hour local time`() {
        val instant = Instant.parse("2026-08-03T14:32:07Z")

        assertEquals("14:32:07", listTimestampTextFor(instant, zone, locale))
    }

    @Test
    fun `detail timestamp is a localized full date plus the same 24-hour time`() {
        val instant = Instant.parse("2026-08-03T14:32:07Z")

        assertEquals("August 3, 2026 14:32:07", detailTimestampTextFor(instant, zone, locale))
    }

    @Test
    fun `detail timestamp in a different zone reflects the local wall-clock time in that zone`() {
        val instant = Instant.parse("2026-08-03T23:32:07Z")

        val result = detailTimestampTextFor(instant, ZoneId.of("Pacific/Kiritimati"), locale)

        // Pacific/Kiritimati is UTC+14 — 23:32:07 UTC lands on the next calendar day, 13:32:07 local.
        assertEquals("August 4, 2026 13:32:07", result)
    }

    @Test
    fun `detail timestamp in Croatian locale contains the year and the 24-hour time`() {
        val instant = Instant.parse("2026-08-03T14:32:07Z")

        val result = detailTimestampTextFor(instant, zone, Locale.forLanguageTag("hr"))

        assertTrue(result.contains("2026"))
        assertTrue(result.endsWith("14:32:07"))
    }

    @Test
    fun `a date equal to today is reported as Today with a short localized date`() {
        val today = LocalDate.of(2026, 8, 3)

        val result = sectionHeaderDateFor(today, today, locale)

        assertEquals(SectionHeaderDate.Today("8/3/26"), result)
    }

    @Test
    fun `a date before today is reported as OtherDate with a long localized date`() {
        val date = LocalDate.of(2026, 8, 2)
        val today = LocalDate.of(2026, 8, 3)

        val result = sectionHeaderDateFor(date, today, locale)

        assertEquals(SectionHeaderDate.OtherDate("August 2, 2026"), result)
    }
}
