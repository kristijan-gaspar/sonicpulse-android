package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.service.MonitoringService
import hr.sonicpulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private val MonitoringPermissions = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Captured fresh right before every launch(), from *before* this specific request — see
    // requestPermissionsAndMaybeStart(). Consumed once the system dialog's result comes back.
    var requestedBeforeSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val decisions = MonitoringPermissions.associateWith { permission ->
            PermissionDecisionEvaluator.evaluate(
                granted = results[permission] == true,
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission),
                requestedBefore = requestedBeforeSnapshot[permission] == true
            )
        }
        scope.launch { MonitoringPermissions.forEach { viewModel.markPermissionRequested(it) } }

        when (
            MonitoringPermissionEvaluator.evaluate(
                microphone = decisions.getValue(Manifest.permission.RECORD_AUDIO),
                fineLocation = decisions.getValue(Manifest.permission.ACCESS_FINE_LOCATION),
                coarseLocation = decisions.getValue(Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        ) {
            // Enough to start (§2.8) — the service's own MonitoringStartupGate re-validates and
            // reports a specific MonitoringStartupFailure if something is still actually missing,
            // so no duplicate handling is needed here for either branch.
            MonitoringPermissionOutcome.Granted, MonitoringPermissionOutcome.ApproximateLocationOnly ->
                startMonitoring(context)
            // Not enough to start, but the user can just tap Start again — nothing else to do.
            MonitoringPermissionOutcome.Denied -> Unit
            MonitoringPermissionOutcome.PermanentlyDenied -> scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.permission_permanently_denied_message),
                    actionLabel = context.getString(R.string.action_open_settings),
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openAppSettings(context)
                }
            }
        }
    }

    fun requestPermissionsAndMaybeStart() {
        scope.launch {
            requestedBeforeSnapshot = MonitoringPermissions.associateWith { viewModel.hasRequestedPermissionBefore(it) }
            permissionLauncher.launch(MonitoringPermissions)
        }
    }

    // POST_NOTIFICATIONS (API 33+) governs only whether the mandatory persistent notification is
    // actually *visible* (plan §2.3) — it is never a prerequisite for starting the service, so
    // its result (granted, denied, or not applicable pre-33) is deliberately ignored here.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(uiState.errorEventId) {
        uiState.errorMessageRes?.let { resId -> snackbarHostState.showSnackbar(context.getString(resId)) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        MonitoringContent(
            uiState = uiState,
            onStart = {
                requestNotificationPermissionIfNeeded()
                requestPermissionsAndMaybeStart()
            },
            onStop = { stopMonitoring(context) },
            onEnableLocation = { requestPermissionsAndMaybeStart() },
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
internal fun MonitoringContent(
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
        if (uiState.serverConfigurationError) {
            ServerConfigurationWarning()
        }
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

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}
