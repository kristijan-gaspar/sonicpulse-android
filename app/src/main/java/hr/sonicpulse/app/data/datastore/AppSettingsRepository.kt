package hr.sonicpulse.app.data.datastore

import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Persisted user-facing app preferences (theme, language, detection alert toggles) — never server
 * configuration, engine thresholds or secrets, all of which stay in BuildConfig/local.properties. */
interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setDetectionNotificationEnabled(enabled: Boolean)
    suspend fun setDetectionVibrationEnabled(enabled: Boolean)
}
