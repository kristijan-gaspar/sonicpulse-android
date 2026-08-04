package hr.sonicpulse.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Dark is the product default — not tied to the system setting unless the user explicitly picks
 * "System" in Settings. [darkTheme] is always a plain boolean the caller has already resolved (see
 * [resolveDarkTheme]) — this composable owns no theme state of its own. No dynamic (Material You)
 * color — the palette is a fixed brand identity, not derived from the user's wallpaper.
 */
@Composable
fun SonicPulseTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
