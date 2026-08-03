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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.service.MonitoringService
import hr.sonicpulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Start flow: one deterministic RequestMultiplePermissions launch, never two. ---

    // Captured fresh right before each launch(), from *before* this specific request — consumed
    // once the system dialog's result comes back, so a permanently-denied classification can never
    // be confused with "never asked yet" (both look like shouldShowRationale == false).
    var startRequestedBeforeSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // POST_NOTIFICATIONS may be present in `results` (API 33+) but is intentionally never
        // looked up below — it must never influence whether monitoring is allowed to start.
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
        // Only the permissions actually included in this request (results.keys) are marked —
        // on API < 33 that's mic+fine+coarse only, POST_NOTIFICATIONS is simply never a key.
        scope.launch { results.keys.forEach { viewModel.markPermissionRequested(it) } }

        when (MonitoringPermissionEvaluator.evaluate(microphone, fineLocation, coarseLocation)) {
            // Enough to start — the service's own MonitoringStartupGate re-validates and reports
            // a specific MonitoringStartupFailure if something is still actually missing, so no
            // duplicate handling is needed here for either branch.
            MonitoringPermissionOutcome.Granted, MonitoringPermissionOutcome.ApproximateLocationOnly ->
                startMonitoring(context)
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
            startRequestedBeforeSnapshot = permissions.associateWith { viewModel.hasRequestedPermissionBefore(it) }
            startPermissionLauncher.launch(permissions)
        }
    }

    // --- Precise-location upgrade flow: a separate request, never reuses the Start flow. ---
    // Monitoring is already active in MonitoringPhase.PreciseLocationRequired — this must never
    // request microphone or notifications again, and success must never re-send a Start intent.

    var upgradeFineRequestedBefore by remember { mutableStateOf(false) }

    // Tracks whether Settings was opened specifically from this flow (not the Start flow's own
    // PermanentlyDenied branch) — rememberSaveable so it survives the process death that opening
    // Settings can trigger. Only this flag may ever cause a refresh-location intent on resume.
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

    LaunchedEffect(uiState.errorEventId) {
        uiState.errorMessageRes?.let { resId -> snackbarHostState.showSnackbar(context.getString(resId)) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        MonitoringContent(
            uiState = uiState,
            onStart = { requestStartPermissions() },
            onStop = { stopMonitoring(context) },
            onEnableLocation = { requestPreciseLocationUpgrade() },
            modifier = Modifier.padding(innerPadding)
        )
    }
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
