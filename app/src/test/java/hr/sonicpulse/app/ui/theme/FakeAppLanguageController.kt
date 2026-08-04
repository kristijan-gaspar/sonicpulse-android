package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.AppLanguage

class FakeAppLanguageController(initial: AppLanguage = AppLanguage.Croatian) : AppLanguageController {

    private var current = initial
    val setLanguageCalls = mutableListOf<AppLanguage>()

    override fun currentLanguage(): AppLanguage = current

    override fun setLanguage(language: AppLanguage) {
        setLanguageCalls += language
        current = language
    }
}
