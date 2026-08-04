package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.AppLanguage
import java.util.Locale

/**
 * Pure resolution rules for Settings' language selection — no AppCompat/Android dependency, so the
 * decision itself stays plain-JVM testable even though its inputs ultimately come from
 * [AppCompatDelegate][androidx.appcompat.app.AppCompatDelegate] via [DefaultAppLanguageController].
 */
object AppLanguageResolver {

    fun localeTagFor(language: AppLanguage): String = when (language) {
        AppLanguage.Croatian -> "hr"
        AppLanguage.English -> "en"
    }

    /**
     * When [storedApplicationLocaleTags] (AppCompat's own per-app locale list) is non-empty, its
     * first entry is authoritative — a stored selection is never second-guessed against the
     * current system locale. When it's empty (fresh install, or the user never chose a language in
     * this app), falls back to [effectiveLocaleTag] (the current system/default locale): a Croatian
     * effective locale resolves to [AppLanguage.Croatian], every other locale — including an
     * unsupported one — resolves to [AppLanguage.English]. Pure: never itself persists anything.
     */
    fun resolve(storedApplicationLocaleTags: List<String>, effectiveLocaleTag: String): AppLanguage {
        val sourceTag = storedApplicationLocaleTags.firstOrNull() ?: effectiveLocaleTag
        return if (Locale.forLanguageTag(sourceTag).language.equals("hr", ignoreCase = true)) {
            AppLanguage.Croatian
        } else {
            AppLanguage.English
        }
    }
}
