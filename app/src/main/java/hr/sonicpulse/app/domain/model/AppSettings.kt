package hr.sonicpulse.app.domain.model

enum class ThemeMode {
    Dark,
    Light,
    System
}

enum class AppLanguage {
    Croatian,
    English
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val language: AppLanguage = AppLanguage.Croatian,
    val detectionNotificationEnabled: Boolean = false,
    val detectionVibrationEnabled: Boolean = false
)
