package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.AppLanguage

/**
 * Thin abstraction over AppCompat's per-app language APIs. AppCompat's own locale auto-storage
 * (the `AppLocalesMetadataHolderService` manifest entry) is the single persisted source of truth
 * for the application language — this is deliberately never backed by
 * [hr.sonicpulse.app.data.datastore.AppSettingsRepository]/DataStore.
 */
interface AppLanguageController {
    /** The currently effective language: the stored AppCompat application locale if one has ever
     * been set, otherwise resolved from the current system locale (never a synthetic default). */
    fun currentLanguage(): AppLanguage

    /** A no-op if [language] already equals [currentLanguage] — never triggers a redundant locale
     * change/Activity recreation for an already-active selection. */
    fun setLanguage(language: AppLanguage)
}
