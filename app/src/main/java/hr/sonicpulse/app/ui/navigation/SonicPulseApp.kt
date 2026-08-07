package hr.sonicpulse.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hr.sonicpulse.app.ui.components.SonicPulseTopBar
import hr.sonicpulse.app.ui.detections.DetectionsScreen
import hr.sonicpulse.app.ui.map.MapScreen
import hr.sonicpulse.app.ui.monitoring.MonitoringScreen
import hr.sonicpulse.app.ui.settings.SettingsScreen

@Composable
fun SonicPulseApp() {
    val navController = rememberNavController()

    Scaffold(
        topBar = { SonicPulseTopBar() },
        bottomBar = { SonicPulseNavigationBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SonicPulseDestination.Monitoring.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(SonicPulseDestination.Monitoring.route) { MonitoringScreen() }
            composable(SonicPulseDestination.Detections.route) { DetectionsScreen() }
            composable(SonicPulseDestination.Map.route) { MapScreen() }
            composable(SonicPulseDestination.Settings.route) { SettingsScreen() }
        }
    }
}

@Composable
private fun SonicPulseNavigationBar(navController: androidx.navigation.NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        SonicPulseDestination.entries.forEach { destination ->
            val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    // Leaving Map must NOT be saved: with saveState = true, Navigation Compose caps
                    // the popped NavBackStackEntry's lifecycle at CREATED (never DESTROYED) so its
                    // ViewModel/rememberSaveable state survives for restoreState. But MapLibre's own
                    // MapView lifecycle is tied to exactly that same LocalLifecycleOwner (see
                    // MapViewLifecycleObserver in the pinned maplibre-compose 0.13.0) and its
                    // AndroidView creates a brand-new native MapView on every fresh composition —
                    // since the old entry is only ever saved (never destroyed), mapView.onDestroy()
                    // is never called on it, leaking one native map surface per Map tab round trip
                    // and eventually leaving a freshly (re)created MapView's style/tile load stuck
                    // forever. Every other destination keeps normal saveState/restoreState — this
                    // only strips saveState when Map is the entry being left (LEAVING, not
                    // entering: navigating TO Map with restoreState = true is harmless either way,
                    // since Map re-fetches its own data on screen entry regardless).
                    val leavingMap = currentDestination?.hierarchy
                        ?.any { it.route == SonicPulseDestination.Map.route } == true
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = !leavingMap }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}
