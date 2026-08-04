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
import hr.sonicpulse.app.ui.theme.AppLanguageController
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Combines persisted [AppSettings][hr.sonicpulse.app.domain.model.AppSettings] (theme only), the
 * current language from [AppLanguageController], live permission status, monitoring diagnostics,
 * the installation id and the app version into one immutable [SettingsUiState] —
 * [SettingsScreen][SettingsScreen] only ever renders this and forwards user actions back here, it
 * never touches DataStore, a repository or [AppLanguageController] directly (§4).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    monitoringStateRepository: MonitoringStateRepository,
    private val installationIdRepository: InstallationIdRepository,
    private val permissionChecker: PermissionChecker,
    private val appLanguageController: AppLanguageController
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow(currentPermissionStatus())
    private val _installationId = MutableStateFlow<String?>(null)
    // Seeded from AppCompat's own current resolution (stored locale, or the effective system
    // locale if none was ever stored) — never a synthetic AppSettings() default. AppLanguageController
    // exposes no Flow of its own, so this is the ViewModel's locally-cached view of it, updated only
    // by this ViewModel's own setLanguage() calls — nothing else in the app changes the language.
    private val _language = MutableStateFlow(appLanguageController.currentLanguage())

    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsRepository.settings,
        monitoringStateRepository.state,
        _permissionStatus,
        _installationId,
        _language
    ) { settings, monitoringState, permissions, installationId, language ->
        SettingsUiState(
            themeMode = settings.themeMode,
            language = language,
            microphonePermissionGranted = permissions.microphoneGranted,
            preciseLocationPermissionGranted = permissions.preciseLocationGranted,
            successfulSubmissions = monitoringState.submissionCounters.submissionSucceeded,
            localDetections = monitoringState.localDetectionCount,
            networkErrors = monitoringState.submissionCounters.droppedNetwork,
            droppedLocation = droppedLocationCount(monitoringState),
            permissionFailures = monitoringState.submissionCounters.permissionFailures,
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

    /** A no-op — never calls [AppLanguageController.setLanguage] — when [language] already equals
     * the currently effective language. */
    fun setLanguage(language: AppLanguage) {
        if (uiState.value.language == language) return
        appLanguageController.setLanguage(language)
        _language.value = language
    }

    private fun currentPermissionStatus(): PermissionStatus = PermissionStatus(
        microphoneGranted = permissionChecker.isGranted(Manifest.permission.RECORD_AUDIO),
        preciseLocationGranted = permissionChecker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    )
}

private data class PermissionStatus(val microphoneGranted: Boolean, val preciseLocationGranted: Boolean)

private fun droppedLocationCount(state: MonitoringState): Int =
    state.submissionCounters.droppedNoLocation +
        state.submissionCounters.droppedStaleLocation +
        state.submissionCounters.droppedInaccurateLocation
