package hr.sonicpulse.app.ui.monitoring

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hr.sonicpulse.app.R
import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.datastore.PermissionRequestHistory
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
import hr.sonicpulse.app.repository.MonitoringState
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.app.service.LocationRefreshFailure
import hr.sonicpulse.app.service.MonitoringStartupFailure
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    monitoringStateRepository: MonitoringStateRepository,
    private val permissionRequestHistory: PermissionRequestHistory
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

    /** Whether [permission] has ever been requested before this installation — see
     * [PermissionRequestHistory] for why Android itself doesn't expose this. */
    suspend fun hasRequestedPermissionBefore(permission: String): Boolean =
        permissionRequestHistory.hasRequestedBefore(permission)

    suspend fun markPermissionRequested(permission: String) {
        permissionRequestHistory.markRequested(permission)
    }
}

private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun MonitoringState.toUiState(): MonitoringUiState = MonitoringUiState(
    phase = computePhase(this),
    locationDisplayState = computeLocationDisplayState(this),
    microphoneActive = isMonitoring,
    backgroundActive = isMonitoring,
    currentDbfs = liveDbfs.toFloat(),
    dbfsHistory = dbfsHistory.map { it.toFloat() },
    lastDetection = sessionDetections.lastOrNull()?.toUiModel(),
    serverConfigurationError = serverConfigurationError,
    errorMessageRes = errorMessageRes(this),
    errorEventId = errorEventId
)

private fun computePhase(state: MonitoringState): MonitoringPhase {
    if (!state.isMonitoring) return MonitoringPhase.Idle
    // Mirrors computeLocationDisplayState's ordering: with services off, no fix can arrive and
    // "precise location required" wouldn't make sense either — this must never fall through to
    // Listening just because a fix from before the services were disabled is still cached.
    if (!state.locationServicesEnabled) return MonitoringPhase.AcquiringLocation
    if (isPreciseLocationRequired(state)) return MonitoringPhase.PreciseLocationRequired
    return if (state.currentLocationSnapshot is LocationSnapshot.Valid) {
        MonitoringPhase.Listening
    } else {
        MonitoringPhase.AcquiringLocation
    }
}

private fun computeLocationDisplayState(state: MonitoringState): LocationDisplayState = when {
    !state.isMonitoring -> LocationDisplayState.Unavailable
    !state.locationServicesEnabled -> LocationDisplayState.Unavailable
    isPreciseLocationRequired(state) -> LocationDisplayState.PreciseRequired
    state.currentLocationSnapshot is LocationSnapshot.Valid -> LocationDisplayState.Gps
    else -> LocationDisplayState.Searching
}

/**
 * With approximate-only permission, every fix genuinely will exceed [hr.sonicpulse.app.data.location.LocationPolicy]'s
 * accuracy threshold — a single Inaccurate reading combined with COARSE permission is therefore
 * a reliable signal on its own, not a guess that would need a running history of fixes.
 */
private fun isPreciseLocationRequired(state: MonitoringState): Boolean =
    state.locationPermissionLevel == LocationPermissionLevel.COARSE &&
        state.currentLocationSnapshot is LocationSnapshot.Inaccurate

private fun errorMessageRes(state: MonitoringState): Int? {
    state.captureError?.let { return captureErrorMessageRes(it) }
    state.startupError?.let { return startupErrorMessageRes(it) }
    state.locationRefreshError?.let { return locationRefreshErrorMessageRes(it) }
    return null
}

private fun captureErrorMessageRes(error: AudioCaptureError): Int = when (error) {
    AudioCaptureError.PermissionDenied -> R.string.error_startup_microphone_denied
    AudioCaptureError.UnsupportedConfiguration,
    is AudioCaptureError.ReadFailure,
    is AudioCaptureError.Unexpected -> R.string.error_capture_generic
}

private fun startupErrorMessageRes(failure: MonitoringStartupFailure): Int = when (failure) {
    MonitoringStartupFailure.MicrophonePermissionDenied -> R.string.error_startup_microphone_denied
    MonitoringStartupFailure.LocationPermissionDenied -> R.string.error_startup_location_denied
    MonitoringStartupFailure.LocationServicesDisabled -> R.string.error_startup_location_services_disabled
    is MonitoringStartupFailure.LocationStartFailed -> R.string.error_startup_location_start_failed
    is MonitoringStartupFailure.ForegroundStartFailed -> R.string.error_startup_foreground_failed
}

private fun locationRefreshErrorMessageRes(failure: LocationRefreshFailure): Int = when (failure) {
    LocationRefreshFailure.PermissionDenied -> R.string.error_startup_location_denied
    LocationRefreshFailure.LocationServicesDisabled -> R.string.error_startup_location_services_disabled
    LocationRefreshFailure.Failed -> R.string.error_location_refresh_failed
}

private fun SessionDetection.toUiModel(): DetectionUiModel {
    val validLocation = location as? LocationSnapshot.Valid
    return DetectionUiModel(
        peakDbfs = peakDbfs,
        timestampText = TimestampFormatter.format(peakTimeClient),
        latitudeText = validLocation?.let { formatCoordinate(it.latitude) },
        longitudeText = validLocation?.let { formatCoordinate(it.longitude) },
        sendResult = sendResultOf(submissionStatus)
    )
}

/** Fixed 5-decimal precision, always dot-separated regardless of the app's display language —
 * coordinates are technical data, not a localized number. */
private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

private fun sendResultOf(status: SubmissionStatus): SendResult = when (status) {
    SubmissionStatus.Pending -> SendResult.Sending
    SubmissionStatus.Sent -> SendResult.Sent
    is SubmissionStatus.Failed -> sendResultForFailure(status.reason)
}

/** Preserves the distinctions the UI must show, without leaking raw HTTP codes,
 * exceptions or other protocol/diagnostic detail — those stay in the repository's counters. */
private fun sendResultForFailure(reason: SubmissionFailureReason): SendResult = when (reason) {
    SubmissionFailureReason.NoLocation,
    SubmissionFailureReason.StaleLocation,
    SubmissionFailureReason.InaccurateLocation -> SendResult.FailedNoLocation
    SubmissionFailureReason.NetworkError -> SendResult.FailedNetwork
    SubmissionFailureReason.Unauthorized -> SendResult.FailedServerConfig
    SubmissionFailureReason.LocalStorageError,
    SubmissionFailureReason.Cancelled,
    SubmissionFailureReason.BadRequest,
    is SubmissionFailureReason.RateLimited,
    is SubmissionFailureReason.ClientError,
    is SubmissionFailureReason.ServerError,
    is SubmissionFailureReason.UnexpectedHttpStatus -> SendResult.FailedOther
}
