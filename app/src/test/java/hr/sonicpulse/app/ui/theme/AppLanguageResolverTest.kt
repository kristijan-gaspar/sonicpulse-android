package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageResolverTest {

    @Test
    fun `Croatian maps to the hr language tag`() {
        assertEquals("hr", AppLanguageResolver.localeTagFor(AppLanguage.Croatian))
    }

    @Test
    fun `English maps to the en language tag`() {
        assertEquals("en", AppLanguageResolver.localeTagFor(AppLanguage.English))
    }

    @Test
    fun `an explicit Croatian stored locale resolves to Croatian regardless of the effective locale`() {
        val result = AppLanguageResolver.resolve(storedApplicationLocaleTags = listOf("hr"), effectiveLocaleTag = "en-US")

        assertEquals(AppLanguage.Croatian, result)
    }

    @Test
    fun `an explicit English stored locale resolves to English regardless of the effective locale`() {
        val result = AppLanguageResolver.resolve(storedApplicationLocaleTags = listOf("en"), effectiveLocaleTag = "hr-HR")

        assertEquals(AppLanguage.English, result)
    }

    @Test
    fun `an empty stored locale list with a Croatian effective locale resolves to Croatian`() {
        val result = AppLanguageResolver.resolve(storedApplicationLocaleTags = emptyList(), effectiveLocaleTag = "hr-HR")

        assertEquals(AppLanguage.Croatian, result)
    }

    @Test
    fun `an empty stored locale list with an English effective locale resolves to English`() {
        val result = AppLanguageResolver.resolve(storedApplicationLocaleTags = emptyList(), effectiveLocaleTag = "en-US")

        assertEquals(AppLanguage.English, result)
    }

    @Test
    fun `an empty stored locale list with an unsupported effective locale falls back to English`() {
        val result = AppLanguageResolver.resolve(storedApplicationLocaleTags = emptyList(), effectiveLocaleTag = "de-DE")

        assertEquals(AppLanguage.English, result)
    }
}
