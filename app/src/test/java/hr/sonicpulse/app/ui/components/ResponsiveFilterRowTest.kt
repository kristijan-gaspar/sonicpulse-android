package hr.sonicpulse.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the pure width comparison ResponsiveFilterRow's SubcomposeLayout measure block delegates
 * to — the actual measurement (the chip row's natural width, the available width) is real Compose
 * layout and can only be verified on-device/in a Compose UI test, but the decision rule itself
 * (fits vs. doesn't) is plain arithmetic and belongs here.
 */
class ResponsiveFilterRowTest {

    @Test
    fun `narrower content than available width fits`() {
        assertTrue(contentFitsAvailableWidth(naturalWidthPx = 300, availableWidthPx = 400))
    }

    @Test
    fun `content exactly matching available width fits`() {
        assertTrue(contentFitsAvailableWidth(naturalWidthPx = 400, availableWidthPx = 400))
    }

    @Test
    fun `content wider than available width does not fit`() {
        assertFalse(contentFitsAvailableWidth(naturalWidthPx = 401, availableWidthPx = 400))
    }

    @Test
    fun `an unbounded available width always fits, regardless of content width`() {
        // Constraints.Infinity is defined as Int.MAX_VALUE — asserted directly here rather than
        // importing androidx.compose.ui.unit.Constraints, to keep this test plain-JVM.
        assertTrue(contentFitsAvailableWidth(naturalWidthPx = Int.MAX_VALUE - 1, availableWidthPx = Int.MAX_VALUE))
    }

    @Test
    fun `zero-width content always fits`() {
        assertTrue(contentFitsAvailableWidth(naturalWidthPx = 0, availableWidthPx = 0))
    }
}
