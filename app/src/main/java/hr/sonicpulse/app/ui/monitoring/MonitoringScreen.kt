package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import hr.sonicpulse.app.service.MonitoringService
import hr.sonicpulse.app.ui.theme.Spacing

private val MonitoringPermissions = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The system permission dialog is requested here (not deferred to a later screen) because
    // MonitoringService can only be started while the app is visible (plan §2.11) — this Start
    // button is that visible moment. Whatever the outcome, startForegroundService() is attempted
    // right after: MonitoringStartupGate re-validates and reports the specific failure via
    // uiState.errorMessageRes if a permission is still missing, so no separate handling is needed here.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startMonitoring(context) }

    LaunchedEffect(uiState.errorEventId) {
        uiState.errorMessageRes?.let { resId -> snackbarHostState.showSnackbar(context.getString(resId)) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        MonitoringContent(
            uiState = uiState,
            onStart = { permissionLauncher.launch(MonitoringPermissions) },
            onStop = { stopMonitoring(context) },
            onEnableLocation = { permissionLauncher.launch(MonitoringPermissions) },
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun MonitoringContent(
    uiState: MonitoringUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        DbfsRing(
            currentDbfs = uiState.currentDbfs,
            active = uiState.phase != MonitoringPhase.Idle,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        StatusPill(uiState.phase, modifier = Modifier.align(Alignment.CenterHorizontally))
        MonitoringHintText(
            uiState.phase,
            modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally)
        )
        MonitoringActionButton(
            phase = uiState.phase,
            onStart = onStart,
            onStop = onStop,
            onEnableLocation = onEnableLocation
        )
        LiveDbfsChart(currentDbfs = uiState.currentDbfs, dbfsHistory = uiState.dbfsHistory)
        LocationStatusRow(
            microphoneActive = uiState.microphoneActive,
            locationDisplayState = uiState.locationDisplayState,
            backgroundActive = uiState.backgroundActive
        )
        LastDetectionCard(uiState.lastDetection)
    }
}

private fun startMonitoring(context: Context) {
    context.startForegroundService(MonitoringService.startIntent(context))
}

private fun stopMonitoring(context: Context) {
    context.startService(MonitoringService.stopIntent(context))
}
