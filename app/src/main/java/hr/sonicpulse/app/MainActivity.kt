package hr.sonicpulse.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.ui.navigation.SonicPulseApp
import hr.sonicpulse.app.ui.theme.SonicPulseTheme
import hr.sonicpulse.app.ui.theme.resolveDarkTheme
import javax.inject.Inject

/** Matches [androidx.activity.enableEdgeToEdge]'s own (package-internal, so not directly reusable)
 * default navigation-bar scrim colors — only the *appearance selection* below changes (system-
 * detected → driven by this app's own resolved theme), not these values. */
private val NavigationBarLightScrim = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavigationBarDarkScrim = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)

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
        setContent {
            val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val darkTheme = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme())

            // enableEdgeToEdge()'s own no-arg default (SystemBarStyle.auto(...)) picks status/nav
            // bar icon color from the *system's* current day/night state, not this app's actually
            // resolved theme — the two can disagree (Dark is SonicPulse's product default
            // regardless of the system setting, and the user can also pick Light/Dark explicitly
            // in Settings independent of the system), which is exactly what made the status bar
            // icons unreadable. Passing an explicit dark()/light() style tied to `darkTheme` fixes
            // that; re-running this in a SideEffect on every (re)composition — cheap and
            // idempotent — keeps it correct when the theme changes later without an Activity
            // recreation (e.g. switching Theme in Settings while this screen is on top).
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(NavigationBarDarkScrim)
                    } else {
                        SystemBarStyle.light(NavigationBarLightScrim, NavigationBarDarkScrim)
                    }
                )
            }

            SonicPulseTheme(darkTheme = darkTheme) {
                SonicPulseApp()
            }
        }
    }
}
