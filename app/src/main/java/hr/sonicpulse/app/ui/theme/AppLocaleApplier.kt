package hr.sonicpulse.app.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import hr.sonicpulse.app.domain.model.AppLanguage

/**
 * Applies the persisted [AppLanguage] as the app's per-app language via
 * [AppCompatDelegate.setApplicationLocales] — works app-wide since AppCompat 1.6.0 regardless of
 * whether the Activity extends `AppCompatActivity`, and automatically defers to the Android 13+
 * platform `LocaleManager` when available. AppCompat also persists the applied locale itself
 * (independent of [hr.sonicpulse.app.data.datastore.AppSettingsRepository]'s own DataStore
 * persistence), so the actual rendered language already survives a process restart on its own —
 * this is only called reactively as [hr.sonicpulse.app.domain.model.AppSettings.language] changes.
 */
object AppLocaleApplier {

    fun localeTagFor(language: AppLanguage): String = when (language) {
        AppLanguage.Croatian -> "hr"
        AppLanguage.English -> "en"
    }

    /** Pure: true only when [desired] genuinely differs from [current] — an already-active language
     * selection must be a no-op, never trigger a redundant locale change/Activity recreation. */
    fun shouldApply(current: LocaleListCompat, desired: LocaleListCompat): Boolean = current != desired

    fun apply(language: AppLanguage) {
        val desired = LocaleListCompat.forLanguageTags(localeTagFor(language))
        if (shouldApply(AppCompatDelegate.getApplicationLocales(), desired)) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}
