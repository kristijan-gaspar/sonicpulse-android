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
    /** Uncapped count of local detections this session — unlike [sessionDetections], which
     * intentionally retains only the latest [hr.sonicpulse.app.repository.SessionDetectionRetention.MAX_SESSION_DETECTIONS],
     * this never shrinks as older entries are evicted and is never affected by a later submission
     * outcome (Settings §4). */
    val localDetectionCount: Int = 0,
    val captureError: AudioCaptureError? = null,
    val startupError: MonitoringStartupFailure? = null,
    /** Set when a bug in block processing (engine, session logging, detection construction —
     * never AudioRecorder itself) forced the session to stop. Kept distinct from [captureError]
     * so the two are never conflated in diagnostics or in the message shown to the user. */
    val processingError: Boolean = false,
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
