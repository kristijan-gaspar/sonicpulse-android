package hr.sonicpulse.app.ui.theme

import hr.sonicpulse.app.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleApplierTest {

    @Test
    fun `Croatian maps to the hr language tag`() {
        assertEquals("hr", AppLocaleApplier.localeTagFor(AppLanguage.Croatian))
    }

    @Test
    fun `English maps to the en language tag`() {
        assertEquals("en", AppLocaleApplier.localeTagFor(AppLanguage.English))
    }
}
