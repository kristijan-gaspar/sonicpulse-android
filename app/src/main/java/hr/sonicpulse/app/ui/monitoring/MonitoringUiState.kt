package hr.sonicpulse.app.ui.monitoring

import androidx.annotation.StringRes

/**
 * Fully display-shaped state for the Monitoring screen (design spec §5.1) — colors and layout
 * decisions still live in the Composable, but every value here is already the thing to render,
 * never a raw domain/repository type. The Composable only renders what it receives.
 */
data class MonitoringUiState(
    val phase: MonitoringPhase = MonitoringPhase.Idle,
    val locationDisplayState: LocationDisplayState = LocationDisplayState.Unavailable,
    val microphoneActive: Boolean = false,
    val backgroundActive: Boolean = false,
    val currentDbfs: Float = -120f,
    val dbfsHistory: List<Float> = emptyList(),
    val lastDetection: DetectionUiModel? = null,
    @StringRes val errorMessageRes: Int? = null,
    /** Changes on every reported failure, even repeats of the same errorMessageRes — lets the
     * Composable key a one-shot Snackbar on this instead of on errorMessageRes, which wouldn't
     * change (and so wouldn't re-trigger) for two identical failures in a row. */
    val errorEventId: Int = 0
)
