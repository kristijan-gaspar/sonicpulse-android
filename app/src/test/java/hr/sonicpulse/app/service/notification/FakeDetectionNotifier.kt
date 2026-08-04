package hr.sonicpulse.app.service.notification

import hr.sonicpulse.app.domain.model.SessionDetection

class FakeDetectionNotifier : DetectionNotifier {
    val notified = mutableListOf<SessionDetection>()

    override fun notify(detection: SessionDetection) {
        notified += detection
    }
}
