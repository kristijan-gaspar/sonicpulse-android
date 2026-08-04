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
import hr.sonicpulse.app.ui.components.ComingSoonScreen
import hr.sonicpulse.app.ui.components.SonicPulseTopBar
import hr.sonicpulse.app.ui.detections.DetectionsScreen
import hr.sonicpulse.app.ui.map.MapScreen
import hr.sonicpulse.app.ui.monitoring.MonitoringScreen

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
            composable(SonicPulseDestination.Settings.route) { ComingSoonScreen() }
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
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
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
