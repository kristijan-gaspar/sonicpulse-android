package hr.sonicpulse.app.ui.settings

import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.ThemeMode

/** Fully display-shaped state for the Settings screen — the Composable only renders what it
 * receives here and forwards user actions back to [SettingsViewModel], it never reads
 * DataStore/repositories itself. */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val language: AppLanguage = AppLanguage.Croatian,
    val microphonePermissionGranted: Boolean = false,
    /** Granted only for `ACCESS_FINE_LOCATION` specifically — an approximate-only (coarse) grant
     * is never reported as precise-location-granted (Settings §C). */
    val preciseLocationPermissionGranted: Boolean = false,
    /** Null only until the very first read from [hr.sonicpulse.app.data.datastore.InstallationIdRepository]
     * completes — DataStore access itself is bounded and effectively instant, so this is a brief
     * transient rather than a real loading state the UI needs to design around. */
    val installationId: String? = null,
    val versionName: String = ""
)
