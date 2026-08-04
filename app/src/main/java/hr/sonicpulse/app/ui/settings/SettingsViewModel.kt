package hr.sonicpulse.app.ui.settings

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.ThemeMode
import hr.sonicpulse.app.repository.MonitoringState
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.app.ui.permissions.PermissionChecker
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Combines persisted [AppSettings][hr.sonicpulse.app.domain.model.AppSettings], live permission
 * status, monitoring diagnostics, the installation id and the app version into one immutable
 * [SettingsUiState] — [SettingsScreen][SettingsScreen] only ever renders this and forwards user
 * actions back here, it never touches DataStore or a repository directly (§4).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    monitoringStateRepository: MonitoringStateRepository,
    private val installationIdRepository: InstallationIdRepository,
    private val permissionChecker: PermissionChecker
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow(currentPermissionStatus())
    private val _installationId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsRepository.settings,
        monitoringStateRepository.state,
        _permissionStatus,
        _installationId
    ) { settings, monitoringState, permissions, installationId ->
        SettingsUiState(
            themeMode = settings.themeMode,
            language = settings.language,
            detectionNotificationEnabled = settings.detectionNotificationEnabled,
            detectionVibrationEnabled = settings.detectionVibrationEnabled,
            microphonePermissionGranted = permissions.microphoneGranted,
            preciseLocationPermissionGranted = permissions.preciseLocationGranted,
            successfulSubmissions = monitoringState.submissionCounters.submissionSucceeded,
            localDetections = localDetectionCount(monitoringState),
            networkErrors = monitoringState.submissionCounters.droppedNetwork,
            droppedLocation = droppedLocationCount(monitoringState),
            droppedPermissions = monitoringState.submissionCounters.droppedPermission,
            installationId = installationId,
            versionName = BuildConfig.VERSION_NAME
        )
    }.stateIn(
        scope = viewModelScope,
        // Eager, matching MonitoringViewModel: diagnostics/permission status must be correct the
        // instant the screen reads uiState.value, before any collector has actually subscribed.
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(versionName = BuildConfig.VERSION_NAME)
    )

    init {
        viewModelScope.launch {
            _installationId.value = installationIdRepository.getOrCreate()
        }
    }

    /** Called from the screen's own `ON_RESUME` lifecycle observer — never on ordinary
     * recomposition, and never itself starts monitoring, requests a permission or moves the map. */
    fun refreshPermissionStatus() {
        _permissionStatus.value = currentPermissionStatus()
    }

    fun setThemeMode(mode: ThemeMode) {
        if (uiState.value.themeMode == mode) return
        viewModelScope.launch { appSettingsRepository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        if (uiState.value.language == language) return
        viewModelScope.launch { appSettingsRepository.setLanguage(language) }
    }

    fun setDetectionNotificationEnabled(enabled: Boolean) {
        if (uiState.value.detectionNotificationEnabled == enabled) return
        viewModelScope.launch { appSettingsRepository.setDetectionNotificationEnabled(enabled) }
    }

    fun setDetectionVibrationEnabled(enabled: Boolean) {
        if (uiState.value.detectionVibrationEnabled == enabled) return
        viewModelScope.launch { appSettingsRepository.setDetectionVibrationEnabled(enabled) }
    }

    private fun currentPermissionStatus(): PermissionStatus = PermissionStatus(
        microphoneGranted = permissionChecker.isGranted(Manifest.permission.RECORD_AUDIO),
        preciseLocationGranted = permissionChecker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    )
}

private data class PermissionStatus(val microphoneGranted: Boolean, val preciseLocationGranted: Boolean)

/** Capped by [hr.sonicpulse.app.repository.SessionDetectionRetention] at 100 retained detections —
 * the true count for an exceptionally long session with more local detections than that would be
 * undercounted; there is no separate uncapped counter to read from instead. */
private fun localDetectionCount(state: MonitoringState): Int = state.sessionDetections.size

private fun droppedLocationCount(state: MonitoringState): Int =
    state.submissionCounters.droppedNoLocation +
        state.submissionCounters.droppedStaleLocation +
        state.submissionCounters.droppedInaccurateLocation
