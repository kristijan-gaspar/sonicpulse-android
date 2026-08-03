package hr.sonicpulse.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import hr.sonicpulse.app.R

/** The 4 bottom-navigation destinations (design spec §2). */
enum class SonicPulseDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    Monitoring("monitoring", R.string.nav_monitoring, Icons.Filled.GraphicEq),
    Detections("detections", R.string.nav_detections, Icons.AutoMirrored.Filled.List),
    Map("map", R.string.nav_map, Icons.Filled.Map),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings)
}
