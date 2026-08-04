package hr.sonicpulse.app.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.sonicpulse.app.domain.model.AppLanguage
import java.util.Locale
import javax.inject.Inject

class DefaultAppLanguageController @Inject constructor(
    @ApplicationContext private val context: Context
) : AppLanguageController {

    override fun currentLanguage(): AppLanguage {
        val stored = AppCompatDelegate.getApplicationLocales()
        val storedTags = (0 until stored.size()).mapNotNull { stored[it]?.toLanguageTag() }
        val effectiveTag = context.resources.configuration.locales.get(0)?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
        return AppLanguageResolver.resolve(storedTags, effectiveTag)
    }

    override fun setLanguage(language: AppLanguage) {
        if (currentLanguage() == language) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(AppLanguageResolver.localeTagFor(language)))
    }
}
