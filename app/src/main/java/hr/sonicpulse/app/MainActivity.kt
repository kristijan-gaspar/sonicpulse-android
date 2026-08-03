package hr.sonicpulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.ui.navigation.SonicPulseApp
import hr.sonicpulse.app.ui.theme.SonicPulseTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonicPulseTheme {
                SonicPulseApp()
            }
        }
    }
}
