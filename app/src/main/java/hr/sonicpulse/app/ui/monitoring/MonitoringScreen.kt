package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.location.LocationManagerCompat
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.R
import hr.sonicpulse.app.observability.SessionLogExporter
import hr.sonicpulse.app.service.MonitoringService
import hr.sonicpulse.app.ui.permissions.PermissionDecisionEvaluator
import hr.sonicpulse.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.location.LocationManager

@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var startRequestedBeforeSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->

        val microphone = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.RECORD_AUDIO] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            requestedBefore = startRequestedBeforeSnapshot[Manifest.permission.RECORD_AUDIO] == true
        )
        val fineLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
            requestedBefore = startRequestedBeforeSnapshot[Manifest.permission.ACCESS_FINE_LOCATION] == true
        )
        val coarseLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION),
            requestedBefore = startRequestedBeforeSnapshot[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )

        scope.launch { results.keys.forEach { viewModel.markPermissionRequested(it) } }

        when (MonitoringPermissionEvaluator.evaluate(microphone, fineLocation, coarseLocation)) {
            MonitoringPermissionOutcome.Granted,
            MonitoringPermissionOutcome.ApproximateLocationOnly -> {
                if (isLocationServicesEnabled(context)) {
                    startMonitoring(context)
                } else {
                    scope.launch {
                        showLocationServicesDisabledSnackbar(
                            context = context,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }

            MonitoringPermissionOutcome.Denied -> Unit

            MonitoringPermissionOutcome.PermanentlyDenied -> scope.launch {
                showOpenSettingsSnackbar(context, snackbarHostState)
            }
        }
    }

    fun requestStartPermissions() {
        scope.launch {
            val permissions = MonitoringPermissionRequestPlan.startPermissions()
            startRequestedBeforeSnapshot = permissions.associateWith { viewModel.hasRequestedPermissionBefore(it) }
            startPermissionLauncher.launch(permissions)
        }
    }


    var upgradeFineRequestedBefore by remember { mutableStateOf(false) }

    var openedSettingsForPreciseLocation by rememberSaveable { mutableStateOf(false) }

    val preciseLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
            requestedBefore = upgradeFineRequestedBefore
        )
        scope.launch { results.keys.forEach { viewModel.markPermissionRequested(it) } }

        when (PreciseLocationUpgradeEvaluator.evaluate(fineLocation)) {
            PreciseLocationUpgradeOutcome.Granted ->
                if (uiState.microphoneActive) {
                    context.startService(MonitoringService.refreshLocationIntent(context))
                }

            PreciseLocationUpgradeOutcome.Denied -> Unit
            PreciseLocationUpgradeOutcome.PermanentlyDenied -> scope.launch {
                showOpenSettingsSnackbar(
                    context = context,
                    snackbarHostState = snackbarHostState,
                    onOpenSettings = { openedSettingsForPreciseLocation = true }
                )
            }
        }
    }

    fun requestPreciseLocationUpgrade() {
        scope.launch {
            upgradeFineRequestedBefore = viewModel.hasRequestedPermissionBefore(Manifest.permission.ACCESS_FINE_LOCATION)
            preciseLocationLauncher.launch(MonitoringPermissionRequestPlan.preciseLocationUpgradePermissions())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    val currentMonitoringActive = rememberUpdatedState(uiState.microphoneActive)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) {
                return@LifecycleEventObserver
            }
            val fineGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (shouldSendPreciseLocationRefreshOnResume(
                    openedSettingsForPreciseLocation = openedSettingsForPreciseLocation,
                    monitoringActive = currentMonitoringActive.value,
                    fineLocationGranted = fineGranted
                )
            ) {
                openedSettingsForPreciseLocation = false
                context.startService(MonitoringService.refreshLocationIntent(context))
            } else if (openedSettingsForPreciseLocation) {
                openedSettingsForPreciseLocation = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val errorMessage = uiState.errorMessageRes?.let { resId ->
        stringResource(resId)
    }

    LaunchedEffect(uiState.errorEventId) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val sessionLogExportSuccessMessage =
        stringResource(R.string.session_log_export_success)

    val sessionLogExportFailedMessage =
        stringResource(R.string.session_log_export_failed)

    val exportSessionLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.Default) { viewModel.sessionLogJson() }
            val result = if (json != null) {
                SessionLogExporter.write(context.contentResolver, uri, json)
            } else {
                Result.failure(IllegalStateException("No completed session log available"))
            }
            val message = if (result.isSuccess) {
                sessionLogExportSuccessMessage
            } else {
                sessionLogExportFailedMessage
            }

            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        MonitoringContent(
            uiState = uiState,
            onStart = { requestStartPermissions() },
            onStop = { stopMonitoring(context) },
            onEnableLocation = { requestPreciseLocationUpgrade() },
            onExportSessionLog = { exportSessionLogLauncher.launch(suggestedSessionLogFileName()) },
            modifier = Modifier.padding(innerPadding)
        )
    }
}

private fun suggestedSessionLogFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "sonicpulse-session-$timestamp.json"
}

private suspend fun showOpenSettingsSnackbar(
    context: Context,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit = {}
) {
    val result = snackbarHostState.showSnackbar(
        message = context.getString(R.string.permission_permanently_denied_message),
        actionLabel = context.getString(R.string.action_open_settings),
        duration = SnackbarDuration.Long
    )
    if (result == SnackbarResult.ActionPerformed) {
        onOpenSettings()
        openAppSettings(context)
    }
}

private suspend fun showLocationServicesDisabledSnackbar(
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    val result = snackbarHostState.showSnackbar(
        message = context.getString(R.string.error_startup_location_services_disabled),
        actionLabel = context.getString(R.string.action_open_location_settings),
        duration = SnackbarDuration.Long
    )

    if (result == SnackbarResult.ActionPerformed) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}

@Composable
internal fun MonitoringContent(
    uiState: MonitoringUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnableLocation: () -> Unit,
    onExportSessionLog: () -> Unit,
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
            locationDisplayState = uiState.locationDisplayState
        )
        LastDetectionCard(uiState.lastDetection)

        if (BuildConfig.ENABLE_SESSION_LOGGING && uiState.sessionLogAvailable) {
            OutlinedButton(
                onClick = onExportSessionLog,
                enabled = !uiState.microphoneActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_export_session_log))
            }
        }
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

private fun isLocationServicesEnabled(context: Context): Boolean {
    val locationManager =
        context.getSystemService(LocationManager::class.java) ?: return false

    return LocationManagerCompat.isLocationEnabled(locationManager)
}