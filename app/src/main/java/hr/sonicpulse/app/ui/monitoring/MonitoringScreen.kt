package hr.sonicpulse.app.ui.monitoring

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.core.location.LocationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.R
import hr.sonicpulse.app.observability.SessionLogExporter
import hr.sonicpulse.app.service.MonitoringService
import hr.sonicpulse.app.ui.map.findActivity
import hr.sonicpulse.app.ui.permissions.PermissionDecisionEvaluator
import hr.sonicpulse.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Start flow: one deterministic RequestMultiplePermissions launch, never two. ---

    // Captured fresh right before each launch(), from *before* this specific request — consumed
    // once the system dialog's result comes back, so a permanently-denied classification can never
    // be confused with "never asked yet" (both look like shouldShowRationale == false).
    // rememberSaveable (not remember): a system-initiated process kill can happen while the
    // permission dialog is up (the same reason openedSettingsForPreciseLocation below is
    // saveable), and losing this snapshot on restart would misclassify a permanently-denied
    // permission as a plain first-time denial once the pending result is redelivered. One Boolean
    // per permission, not a Map, since rememberSaveable's default saver only covers
    // Bundle-primitive types and the permission set requested here is fixed (see
    // MonitoringPermissionRequestPlan.startPermissions()) — POST_NOTIFICATIONS is deliberately not
    // tracked here since it's never looked up in the callback below.
    var startMicRequestedBefore by rememberSaveable { mutableStateOf(false) }
    var startFineRequestedBefore by rememberSaveable { mutableStateOf(false) }
    var startCoarseRequestedBefore by rememberSaveable { mutableStateOf(false) }

    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (activity == null) {
            // Without an Activity, shouldShowRequestPermissionRationale() cannot be called at
            // all, so a plain first-time denial and a permanently-denied one are indistinguishable
            // here — bail rather than risk misclassifying the former as the latter and routing the
            // user to Settings for no reason. The user can simply tap Start again.
            return@rememberLauncherForActivityResult
        }

        // POST_NOTIFICATIONS may be present in `results` (API 33+) but is intentionally never
        // looked up below — it must never influence whether monitoring is allowed to start.
        val microphone = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.RECORD_AUDIO] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            requestedBefore = startMicRequestedBefore
        )
        val fineLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
            requestedBefore = startFineRequestedBefore
        )
        val coarseLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION),
            requestedBefore = startCoarseRequestedBefore
        )

        // Only the permissions actually included in this request (results.keys) are marked —
        // on API < 33 that's mic+fine+coarse only, POST_NOTIFICATIONS is simply never a key.
        scope.launch { results.keys.forEach { viewModel.markPermissionRequested(it) } }

        when (MonitoringPermissionEvaluator.evaluate(microphone, fineLocation, coarseLocation)) {
            // Enough permission to start, but Location Services itself can still be off even
            // with FINE/COARSE granted — checked here so the UI never calls
            // startForegroundService() in that case (see isLocationServicesEnabled below).
            // Anything else still missing is left to MonitoringStartupGate's own re-validation,
            // which reports a specific MonitoringStartupFailure — no duplicate handling needed
            // here for that.
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

            // Not enough to start, but the user can just tap Start again — nothing else to do.
            MonitoringPermissionOutcome.Denied -> Unit

            MonitoringPermissionOutcome.PermanentlyDenied -> scope.launch {
                showOpenSettingsSnackbar(context, snackbarHostState)
            }
        }
    }

    fun requestStartPermissions() {
        scope.launch {
            val permissions = MonitoringPermissionRequestPlan.startPermissions()
            startMicRequestedBefore = viewModel.hasRequestedPermissionBefore(Manifest.permission.RECORD_AUDIO)
            startFineRequestedBefore = viewModel.hasRequestedPermissionBefore(Manifest.permission.ACCESS_FINE_LOCATION)
            startCoarseRequestedBefore = viewModel.hasRequestedPermissionBefore(Manifest.permission.ACCESS_COARSE_LOCATION)
            startPermissionLauncher.launch(permissions)
        }
    }

    // --- Precise-location upgrade flow: a separate request, never reuses the Start flow. ---
    // Monitoring is already active in MonitoringPhase.PreciseLocationRequired — this must never
    // request microphone or notifications again, and success must never re-send a Start intent.

    // rememberSaveable for the same process-death reason as startFineRequestedBefore above.
    var upgradeFineRequestedBefore by rememberSaveable { mutableStateOf(false) }

    // Tracks whether Settings was opened specifically from this flow (not the Start flow's own
    // PermanentlyDenied branch) — rememberSaveable so it survives the process death that opening
    // Settings can trigger. Only this flag may ever cause a refresh-location intent on resume.
    var openedSettingsForPreciseLocation by rememberSaveable { mutableStateOf(false) }

    val preciseLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (activity == null) {
            // Same reasoning as the Start flow's launcher above: without an Activity we cannot
            // safely classify Denied vs. PermanentlyDenied, so bail rather than risk sending the
            // user to Settings unnecessarily.
            return@rememberLauncherForActivityResult
        }

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

            // Still PreciseLocationRequired; the user can tap "Enable location" again.
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
    // DisposableEffect(lifecycleOwner) only (re)builds its observer when lifecycleOwner itself
    // changes — never on every uiState update — so the observer's closure would otherwise keep
    // whatever microphoneActive value was current when it was first created. rememberUpdatedState
    // gives it a stable holder to read through instead, so ON_RESUME (which can fire long after
    // that first composition, e.g. on returning from system Settings) always sees the current value.
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

    // --- Session log export (testing-only, BuildConfig.ENABLE_SESSION_LOGGING): the button
    // itself is only ever composed when the flag is on (see MonitoringContent) and the launcher
    // is cheap to register regardless, so it's declared unconditionally here like every other
    // launcher on this screen. A null uri means the user cancelled the picker — not a failure,
    // nothing is shown for it. ---
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

        // Hidden entirely (not just disabled) when the build doesn't have session logging, and
        // hidden until a session has actually finished — only "disabled while monitoring is
        // active" is a true enabled/disabled state rather than a presence/absence one.
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
