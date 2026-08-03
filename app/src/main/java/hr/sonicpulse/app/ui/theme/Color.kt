package hr.sonicpulse.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8EF7),
    onPrimary = Color.White,
    background = Color(0xFF0D0F14),
    surface = Color(0xFF161920),
    surfaceVariant = Color(0xFF1E2230),
    outline = Color(0xFF2A2F42),
    onBackground = Color(0xFFE8EAF2),
    onSurface = Color(0xFFE8EAF2),
    onSurfaceVariant = Color(0xFF8B91A8)
)

val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    background = Color(0xFFF1F4FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF7F9FF),
    outline = Color(0xFFE2E8F7),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569)
)

/** Status/data-visualization colors — not part of the Material color scheme itself. */
object SemanticColors {
    val Success = Color(0xFF22C55E)
    val SuccessBg = Success.copy(alpha = 0.12f)
    val Warning = Color(0xFFF59E0B)
    val WarningBg = Warning.copy(alpha = 0.12f)
    val Danger = Color(0xFFEF4444)
    val DangerBg = Danger.copy(alpha = 0.12f)
    val Yellow = Color(0xFFEAB308)
    val YellowBg = Yellow.copy(alpha = 0.12f)
}
