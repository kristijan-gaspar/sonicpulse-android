package hr.sonicpulse.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.ui.navigation.SonicPulseApp
import hr.sonicpulse.app.ui.theme.SonicPulseTheme
import hr.sonicpulse.app.ui.theme.resolveDarkTheme
import javax.inject.Inject

/**
 * Extends [AppCompatActivity], not `ComponentActivity` — required for AppCompat's per-app language
 * support (Settings §3). `AppCompatActivity.attachBaseContext()` applies whatever locale AppCompat's
 * own auto-storage already restored — via the `AppLocalesMetadataHolderService` entry in the
 * manifest — *before* `onCreate()`/`setContent()` run, so the correct language renders on the very
 * first frame. No manual apply call belongs here: doing so would risk a second, redundant
 * recreation and briefly rendering the wrong language first.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

            SonicPulseTheme(darkTheme = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())) {
                SonicPulseApp()
            }
        }
    }
}
