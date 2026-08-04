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
 * current language from [AppLanguageController], live permission status, the installation id and
 * the app version into one immutable [SettingsUiState] — [SettingsScreen][SettingsScreen] only
 * ever renders this and forwards user actions back here, it never touches DataStore, a repository
 * or [AppLanguageController] directly (§4).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val installationIdRepository: InstallationIdRepository,
    private val permissionChecker: PermissionChecker,
    private val appLanguageController: AppLanguageController
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow(currentPermissionStatus())
    private val _installationId = MutableStateFlow<String?>(null)

    private val initialLanguage = appLanguageController.currentLanguage()
    private val _language = MutableStateFlow(initialLanguage)

    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsRepository.settings,
        _permissionStatus,
        _installationId,
        _language
    ) { settings, permissions, installationId, language ->
        SettingsUiState(
            themeMode = settings.themeMode,
            language = language,
            microphonePermissionGranted = permissions.microphoneGranted,
            preciseLocationPermissionGranted = permissions.preciseLocationGranted,
            installationId = installationId,
            versionName = BuildConfig.VERSION_NAME
        )
    }.stateIn(
        scope = viewModelScope,
        // Eager, matching MonitoringViewModel: permission status must be correct the instant the
        // screen reads uiState.value, before any collector has actually subscribed.
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(language = initialLanguage, versionName = BuildConfig.VERSION_NAME)
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
