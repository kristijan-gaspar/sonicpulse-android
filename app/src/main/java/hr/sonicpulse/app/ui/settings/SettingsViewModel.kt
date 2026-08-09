package hr.sonicpulse.app.ui.settings

import android.Manifest
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.ThemeMode
import hr.sonicpulse.app.ui.permissions.LocationServicesChecker
import hr.sonicpulse.app.ui.permissions.PermissionChecker
import hr.sonicpulse.app.ui.theme.AppLanguageController
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Combines persisted [AppSettings][hr.sonicpulse.app.domain.model.AppSettings] (theme only), the
 * current language from [AppLanguageController], live permission and Location Services status, the
 * installation id and the app version into one immutable [SettingsUiState] —
 * [SettingsScreen][SettingsScreen] only ever renders this and forwards user actions back here, it
 * never touches DataStore, a repository or [AppLanguageController] directly (§4).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val installationIdRepository: InstallationIdRepository,
    private val permissionChecker: PermissionChecker,
    private val locationServicesChecker: LocationServicesChecker,
    private val appLanguageController: AppLanguageController
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow(currentDeviceStatus())
    private val _installationId = MutableStateFlow<String?>(null)

    private val initialLanguage = appLanguageController.currentLanguage()
    private val _language = MutableStateFlow(initialLanguage)

    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsRepository.settings,
        _permissionStatus,
        _installationId,
        _language
    ) { settings, status, installationId, language ->
        SettingsUiState(
            themeMode = settings.themeMode,
            language = language,
            microphonePermissionGranted = status.microphoneGranted,
            locationPermissionGranted = status.locationGranted,
            preciseLocationPermissionGranted = status.preciseLocationGranted,
            locationServicesEnabled = status.locationServicesEnabled,
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
            // A read/write failure here (e.g. DataStore's backing file briefly unavailable) must
            // not crash the app — the installation id simply stays unavailable (null, its default
            // above) for this screen; DetectionSubmitter has its own, separate handling of the
            // exact same failure for submission (LocalStorageError), unaffected by this catch.
            _installationId.value = try {
                installationIdRepository.getOrCreate()
            } catch (e: IOException) {
                Log.w(
                    TAG,
                    "Failed to read or create the installation id",
                    e
                )
                null
            }
        }
    }

    /** Called from the screen's own `ON_RESUME` lifecycle observer — never on ordinary
     * recomposition, and never itself starts monitoring, requests a permission, opens Location
     * Settings or moves the map. Re-reads every permission and Location Services state at once, so
     * returning from Android's app-details or location settings immediately reflects the real
     * device state. */
    fun refreshPermissionStatus() {
        _permissionStatus.value = currentDeviceStatus()
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

    private fun currentDeviceStatus(): DeviceStatus {
        val fineGranted = permissionChecker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = permissionChecker.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return DeviceStatus(
            microphoneGranted = permissionChecker.isGranted(Manifest.permission.RECORD_AUDIO),
            locationGranted = fineGranted || coarseGranted,
            preciseLocationGranted = fineGranted,
            locationServicesEnabled = locationServicesChecker.isEnabled()
        )
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}

private data class DeviceStatus(
    val microphoneGranted: Boolean,
    val locationGranted: Boolean,
    val preciseLocationGranted: Boolean,
    val locationServicesEnabled: Boolean
)
