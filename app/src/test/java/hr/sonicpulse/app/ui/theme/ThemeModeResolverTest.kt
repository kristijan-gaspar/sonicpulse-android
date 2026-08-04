package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeResolverTest {

    @Test
    fun `Dark always resolves to dark regardless of the system setting`() {
        assertTrue(resolveDarkTheme(ThemeMode.Dark, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.Dark, systemInDarkTheme = true))
    }

    @Test
    fun `Light always resolves to light regardless of the system setting`() {
        assertFalse(resolveDarkTheme(ThemeMode.Light, systemInDarkTheme = false))
        assertFalse(resolveDarkTheme(ThemeMode.Light, systemInDarkTheme = true))
    }

    @Test
    fun `System follows the current system setting`() {
        assertTrue(resolveDarkTheme(ThemeMode.System, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(ThemeMode.System, systemInDarkTheme = false))
    }
}
