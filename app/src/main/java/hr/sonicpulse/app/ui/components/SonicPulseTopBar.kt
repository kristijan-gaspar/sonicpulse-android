package hr.sonicpulse.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import hr.sonicpulse.app.ui.theme.AppNameStyle

/** Present on all 4 top-level screens — the "Sonic"/"Pulse" wordmark, no actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonicPulseTopBar() {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = AppNameStyle.fontWeight, fontSize = AppNameStyle.fontSize)) {
                            append("Sonic")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = AppNameStyle.fontWeight, fontSize = AppNameStyle.fontSize)) {
                            append("Pulse")
                        }
                    }
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}
