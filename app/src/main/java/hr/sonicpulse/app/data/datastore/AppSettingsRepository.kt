package hr.sonicpulse.app.data.datastore

import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Persisted user-facing app preferences (currently just theme) — never server configuration,
 * engine thresholds or secrets, all of which stay in BuildConfig/local.properties. Language is
 * deliberately not here: AppCompat's own locale storage is the single source of truth for it
 * (see [hr.sonicpulse.app.ui.theme.AppLanguageController]). */
interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
}
