package hr.sonicpulse.app.data.datastore

import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAppSettingsRepository(initial: AppSettings = AppSettings()) : AppSettingsRepository {

    private val _settings = MutableStateFlow(initial)
    override val settings = _settings

    val setThemeModeCalls = mutableListOf<ThemeMode>()

    override suspend fun setThemeMode(mode: ThemeMode) {
        setThemeModeCalls += mode
        _settings.value = _settings.value.copy(themeMode = mode)
    }
}
