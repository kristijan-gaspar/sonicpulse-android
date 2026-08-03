package hr.sonicpulse.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Dark is the product default (design spec §1.1) — not tied to the system setting. Settings
 * (branch feature/settings) will persist a Tamna/Svjetla/Auto preference and pass [darkTheme]
 * accordingly; until then this always renders dark. No dynamic (Material You) color — the
 * palette is a fixed brand identity, not derived from the user's wallpaper.
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
