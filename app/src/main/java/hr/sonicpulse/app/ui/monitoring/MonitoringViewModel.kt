package hr.sonicpulse.app.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.repository.MonitoringState
import hr.sonicpulse.app.repository.MonitoringStateRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    monitoringStateRepository: MonitoringStateRepository
) : ViewModel() {

    val uiState: StateFlow<MonitoringUiState> = monitoringStateRepository.state
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            // Eager, not WhileSubscribed: MonitoringStateRepository is a singleton fed by
            // MonitoringService regardless of whether this screen is visible, so there is no
            // "no one's watching" case worth optimizing for, and eager sharing keeps uiState.value
            // correct even before a collector subscribes.
            started = SharingStarted.Eagerly,
            initialValue = monitoringStateRepository.state.value.toUiState()
        )
}

private fun MonitoringState.toUiState(): MonitoringUiState = MonitoringUiState(
    isMonitoring = isMonitoring,
    liveDbfs = liveDbfs,
    liveBaseline = liveBaseline,
    engineState = engineState,
    sessionDetections = sessionDetections,
    captureError = captureError,
    startupError = startupError,
    submissionCounters = submissionCounters,
    serverConfigurationError = serverConfigurationError
)
