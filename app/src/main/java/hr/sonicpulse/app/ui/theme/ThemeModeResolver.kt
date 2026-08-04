package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.ThemeMode

/** Pure mapping from the persisted [ThemeMode] preference to the boolean [SonicPulseTheme] actually
 * renders — kept separate from the Composable so it's plain-JVM testable. */
fun resolveDarkTheme(mode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> systemInDarkTheme
}
