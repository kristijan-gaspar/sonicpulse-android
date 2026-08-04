package hr.sonicpulse.app.domain.model

enum class ThemeMode {
    Dark,
    Light,
    System
}

/** Not persisted here — AppCompat's own per-app locale storage is the single source of truth for
 * language (see [hr.sonicpulse.app.ui.theme.AppLanguageController]). This enum is still the shared
 * vocabulary Settings displays and lets the user pick from. */
enum class AppLanguage {
    Croatian,
    English
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark
)
