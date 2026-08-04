package hr.sonicpulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.ui.navigation.SonicPulseApp
import hr.sonicpulse.app.ui.theme.AppLocaleApplier
import hr.sonicpulse.app.ui.theme.SonicPulseTheme
import hr.sonicpulse.app.ui.theme.resolveDarkTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

            // Reactive, not a one-shot startup call — a later Settings-screen change must apply
            // immediately without restarting the app. Keyed on the language alone so unrelated
            // recompositions (e.g. theme changing) never re-run this and never re-recreate the
            // Activity; AppLocaleApplier itself is also idempotent if the language is unchanged.
            LaunchedEffect(settings.language) {
                AppLocaleApplier.apply(settings.language)
            }

            SonicPulseTheme(darkTheme = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())) {
                SonicPulseApp()
            }
        }
    }
}
