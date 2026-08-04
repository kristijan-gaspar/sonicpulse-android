package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * source of truth here, there is nothing else to roll back. Never writes a language key — that
 * belongs to AppCompat's own locale storage exclusively (see [AppSettingsRepository]'s KDoc).
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

    private suspend fun writeSafely(transform: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (e: IOException) {
            // Best-effort persistence: the setting simply reverts to its last-persisted value the
            // next time `settings` is read. Never crashes the caller (e.g. a ViewModel action).
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        themeMode = this[THEME_MODE_KEY].toEnumOrDefault(ThemeMode.entries, ThemeMode.Dark)
    )

    private fun <T : Enum<T>> String?.toEnumOrDefault(values: List<T>, default: T): T =
        values.firstOrNull { it.name == this } ?: default

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }
}
