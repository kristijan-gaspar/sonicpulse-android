package hr.sonicpulse.app.ui.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HotspotDetailFormattingTest {

    private val base = Instant.parse("2026-08-03T10:00:00Z")

    @Test
    fun `12 seconds is a Seconds bucket`() {
        val span = hotspotTimeSpanFor(base, base.plusSeconds(12))

        assertEquals(HotspotTimeSpan.Seconds(12), span)
    }

    @Test
    fun `2 minutes 15 seconds is a MinutesSeconds bucket`() {
        val span = hotspotTimeSpanFor(base, base.plusSeconds(135))

        assertEquals(HotspotTimeSpan.MinutesSeconds(2, 15), span)
    }

    @Test
    fun `1 hour 4 minutes is an HoursMinutes bucket`() {
        val span = hotspotTimeSpanFor(base, base.plusSeconds(3840))

        assertEquals(HotspotTimeSpan.HoursMinutes(1, 4), span)
    }

    @Test
    fun `zero duration is 0 seconds`() {
        val span = hotspotTimeSpanFor(base, base)

        assertEquals(HotspotTimeSpan.Seconds(0), span)
    }

    @Test
    fun `exactly 60 seconds is 1 minute 0 seconds, not 60 seconds`() {
        val span = hotspotTimeSpanFor(base, base.plusSeconds(60))

        assertEquals(HotspotTimeSpan.MinutesSeconds(1, 0), span)
    }

    @Test
    fun `exactly 3600 seconds is 1 hour 0 minutes, not 60 minutes`() {
        val span = hotspotTimeSpanFor(base, base.plusSeconds(3600))

        assertEquals(HotspotTimeSpan.HoursMinutes(1, 0), span)
    }

    // --- confidenceScoreText ---

    @Test
    fun `confidence is rendered as a score out of 100`() {
        assertEquals("74 / 100", confidenceScoreText(74))
    }

    @Test
    fun `confidence score never contains a percent sign`() {
        assertFalse(confidenceScoreText(74).contains("%"))
    }

    @Test
    fun `confidence score at the 0 boundary`() {
        assertEquals("0 / 100", confidenceScoreText(0))
    }

    @Test
    fun `confidence score at the 100 boundary`() {
        assertEquals("100 / 100", confidenceScoreText(100))
    }
}
