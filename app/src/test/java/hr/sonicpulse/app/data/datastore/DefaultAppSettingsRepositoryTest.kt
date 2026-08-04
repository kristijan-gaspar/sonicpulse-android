package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultAppSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDataStore(fileName: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { tempFolder.newFile(fileName) })

    @Test
    fun `empty DataStore emits AppSettings defaults`() = runTest {
        val repository = DefaultAppSettingsRepository(newDataStore("a.preferences_pb"))

        val settings = repository.settings.first()

        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `theme persists across a fresh repository instance backed by the same store`() = runTest {
        val dataStore = newDataStore("b.preferences_pb")
        DefaultAppSettingsRepository(dataStore).setThemeMode(ThemeMode.Light)

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(ThemeMode.Light, settings.themeMode)
    }

    @Test
    fun `language persists across a fresh repository instance backed by the same store`() = runTest {
        val dataStore = newDataStore("c.preferences_pb")
        DefaultAppSettingsRepository(dataStore).setLanguage(AppLanguage.English)

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(AppLanguage.English, settings.language)
    }

    @Test
    fun `detection notification toggle persists across a fresh repository instance`() = runTest {
        val dataStore = newDataStore("d.preferences_pb")
        DefaultAppSettingsRepository(dataStore).setDetectionNotificationEnabled(true)

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(true, settings.detectionNotificationEnabled)
    }

    @Test
    fun `vibration toggle persists across a fresh repository instance`() = runTest {
        val dataStore = newDataStore("e.preferences_pb")
        DefaultAppSettingsRepository(dataStore).setDetectionVibrationEnabled(true)

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(true, settings.detectionVibrationEnabled)
    }

    @Test
    fun `an unknown persisted theme value falls back to the default`() = runTest {
        val dataStore = newDataStore("f.preferences_pb")
        dataStore.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("theme_mode")] = "Neon" } }

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(ThemeMode.Dark, settings.themeMode)
    }

    @Test
    fun `an unknown persisted language value falls back to the default`() = runTest {
        val dataStore = newDataStore("g.preferences_pb")
        dataStore.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("language")] = "Klingon" } }

        val settings = DefaultAppSettingsRepository(dataStore).settings.first()

        assertEquals(AppLanguage.Croatian, settings.language)
    }

    @Test
    fun `a write failure does not throw`() = runTest {
        val repository = DefaultAppSettingsRepository(AlwaysFailingDataStore)

        // Must complete without propagating the underlying IOException.
        repository.setThemeMode(ThemeMode.Light)
    }

    /** A [DataStore] whose every write throws — proves [DefaultAppSettingsRepository] swallows a
     * write failure rather than crashing the caller (§8). */
    private object AlwaysFailingDataStore : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw IOException("simulated write failure")
        }
    }
}
