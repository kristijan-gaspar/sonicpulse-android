package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Backed by the same shared installation [DataStore] the app already uses for the installation id
 * and permission-request history (see [DataStoreModule]) — not a second DataStore instance, just
 * more keys in the existing one. An unknown/corrupt stored enum value or a read failure both fall
 * back to [AppSettings]'s defaults rather than propagating, and a write failure is swallowed (§8:
 * "write failure does not crash the app-facing flow") — the DataStore file itself is the only
 * source of truth here, there is nothing else to roll back.
 */
@Singleton
class DefaultAppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : AppSettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> prefs.toAppSettings() }

    override suspend fun setThemeMode(mode: ThemeMode) = writeSafely {
        it[THEME_MODE_KEY] = mode.name
    }

    override suspend fun setLanguage(language: AppLanguage) = writeSafely {
        it[LANGUAGE_KEY] = language.name
    }

    override suspend fun setDetectionNotificationEnabled(enabled: Boolean) = writeSafely {
        it[DETECTION_NOTIFICATION_ENABLED_KEY] = enabled
    }

    override suspend fun setDetectionVibrationEnabled(enabled: Boolean) = writeSafely {
        it[DETECTION_VIBRATION_ENABLED_KEY] = enabled
    }

    private suspend fun writeSafely(transform: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (e: IOException) {
            // Best-effort persistence: the setting simply reverts to its last-persisted value the
            // next time `settings` is read. Never crashes the caller (e.g. a ViewModel action).
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        themeMode = this[THEME_MODE_KEY].toEnumOrDefault(ThemeMode.entries, ThemeMode.Dark),
        language = this[LANGUAGE_KEY].toEnumOrDefault(AppLanguage.entries, AppLanguage.Croatian),
        detectionNotificationEnabled = this[DETECTION_NOTIFICATION_ENABLED_KEY] ?: false,
        detectionVibrationEnabled = this[DETECTION_VIBRATION_ENABLED_KEY] ?: false
    )

    private fun <T : Enum<T>> String?.toEnumOrDefault(values: List<T>, default: T): T =
        values.firstOrNull { it.name == this } ?: default

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val DETECTION_NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("detection_notification_enabled")
        val DETECTION_VIBRATION_ENABLED_KEY = booleanPreferencesKey("detection_vibration_enabled")
    }
}
