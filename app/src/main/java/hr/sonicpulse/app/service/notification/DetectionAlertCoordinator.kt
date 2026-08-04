package hr.sonicpulse.app.service.notification

import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.domain.model.SessionDetection
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Reacts to one local detection with the user's current notification/vibration preferences —
 * always off `MonitoringService`'s own coroutine scope, never the audio capture thread, since
 * reading [AppSettingsRepository] is DataStore I/O (§5B). Deduplicates by [SessionDetection.localEventId]
 * so re-observing the same detection (e.g. a later [hr.sonicpulse.app.repository.MonitoringState]
 * emission where only its submission status changed) never re-alerts — a fresh [UUID] per detection
 * makes this safe across an entire monitoring session without needing an explicit reset.
 */
class DetectionAlertCoordinator @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val detectionNotifier: DetectionNotifier,
    private val detectionVibrator: DetectionVibrator
) {
    private var lastAlertedEventId: UUID? = null

    suspend fun onLocalDetection(detection: SessionDetection) {
        if (detection.localEventId == lastAlertedEventId) {
            return
        }
        lastAlertedEventId = detection.localEventId

        val settings = appSettingsRepository.settings.first()
        if (settings.detectionNotificationEnabled) {
            detectionNotifier.notify(detection)
        }
        if (settings.detectionVibrationEnabled) {
            detectionVibrator.vibrateOnce()
        }
    }
}
