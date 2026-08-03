package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.service.LocationRefreshFailure
import hr.sonicpulse.app.service.MonitoringStartupFailure
import hr.sonicpulse.engine.DetectionState

data class MonitoringState(
    val isMonitoring: Boolean = false,
    val liveDbfs: Double = -120.0,
    val liveBaseline: Double = -120.0,
    val dbfsHistory: List<Double> = emptyList(),
    val engineState: DetectionState = DetectionState.IDLE,
    val sessionDetections: List<SessionDetection> = emptyList(),
    val captureError: AudioCaptureError? = null,
    val startupError: MonitoringStartupFailure? = null,
    /** Bumped on every monitoringFailed/monitoringStartupFailed call, even repeats of the same
     * failure — lets the UI re-show a one-shot error notification instead of keying purely off
     * captureError/startupError's value, which wouldn't change for two identical failures in a row. */
    val errorEventId: Int = 0,
    /** Set only by a nonfatal ACTION_REFRESH_LOCATION outcome — unlike [captureError]/[startupError],
     * never accompanies isMonitoring being set to false. */
    val locationRefreshError: LocationRefreshFailure? = null,
    val submissionCounters: SubmissionCounters = SubmissionCounters(),
    val serverConfigurationError: Boolean = false,
    val currentLocationSnapshot: LocationSnapshot = LocationSnapshot.NoFixYet,
    val locationPermissionLevel: LocationPermissionLevel = LocationPermissionLevel.NONE,
    val locationServicesEnabled: Boolean = true
)
