package hr.sonicpulse.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Default Material typography, overridden ad hoc (design spec §1.2) rather than as a global scheme change. */
val Typography = Typography()

/** Numeric/data values (dBFS readings, timestamps, coordinates) — design spec §1.2. */
val MonospaceValueStyle = TextStyle(fontFamily = FontFamily.Monospace)

/** "SonicPulse" wordmark — design spec §3: "Sonic" in onSurface + "Pulse" in primary. */
val AppNameStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
