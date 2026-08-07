package hr.sonicpulse.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

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
            modifier = Modifier.padding(innerPadding),
            enterTransition = { sonicPulseEnterTransition() }

        ) {
            composable(SonicPulseDestination.Monitoring.route) {
                OpaqueDestination { MonitoringScreen() }
            }

            composable(SonicPulseDestination.Detections.route) {
                OpaqueDestination { DetectionsScreen() }
            }

            composable(SonicPulseDestination.Map.route) {
                MapScreen()
            }

            composable(SonicPulseDestination.Settings.route) {
                OpaqueDestination { SettingsScreen() }
            }
        }
    }
}

private const val NAVIGATION_ANIMATION_DURATION_MS = 200

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sonicPulseEnterTransition(): EnterTransition {
    return if (involvesMap()) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sonicPulseExitTransition(): ExitTransition {
    return if (involvesMap()) {
        ExitTransition.None
    } else {
        fadeOut(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesMap(): Boolean {
    return initialState.destination.route == SonicPulseDestination.Map.route ||
            targetState.destination.route == SonicPulseDestination.Map.route
}

@Composable
private fun OpaqueDestination(
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        content()
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
                    if (!selected) {
                        val leavingMap = currentDestination?.hierarchy
                            ?.any { it.route == SonicPulseDestination.Map.route } == true
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = !leavingMap }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}
