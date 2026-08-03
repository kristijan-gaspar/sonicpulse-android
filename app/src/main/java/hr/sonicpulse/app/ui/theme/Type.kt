package hr.sonicpulse.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Default Material typography, overridden ad hoc per-component rather than as a global scheme change. */
val Typography = Typography()

/** Numeric/data values: dBFS readings, timestamps, coordinates. */
val MonospaceValueStyle = TextStyle(fontFamily = FontFamily.Monospace)

/** "SonicPulse" wordmark: "Sonic" in onSurface + "Pulse" in primary. */
val AppNameStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
