package hr.sonicpulse.app.data.datastore

import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAppSettingsRepository(initial: AppSettings = AppSettings()) : AppSettingsRepository {

    private val _settings = MutableStateFlow(initial)
    override val settings = _settings

    val setThemeModeCalls = mutableListOf<ThemeMode>()
    val setLanguageCalls = mutableListOf<AppLanguage>()
    val setDetectionNotificationEnabledCalls = mutableListOf<Boolean>()
    val setDetectionVibrationEnabledCalls = mutableListOf<Boolean>()

    override suspend fun setThemeMode(mode: ThemeMode) {
        setThemeModeCalls += mode
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    override suspend fun setLanguage(language: AppLanguage) {
        setLanguageCalls += language
        _settings.value = _settings.value.copy(language = language)
    }

    override suspend fun setDetectionNotificationEnabled(enabled: Boolean) {
        setDetectionNotificationEnabledCalls += enabled
        _settings.value = _settings.value.copy(detectionNotificationEnabled = enabled)
    }

    override suspend fun setDetectionVibrationEnabled(enabled: Boolean) {
        setDetectionVibrationEnabledCalls += enabled
        _settings.value = _settings.value.copy(detectionVibrationEnabled = enabled)
    }
}
