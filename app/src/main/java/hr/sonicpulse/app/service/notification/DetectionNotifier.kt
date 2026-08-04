package hr.sonicpulse.app.service.notification

import hr.sonicpulse.app.domain.model.SessionDetection

/** Posts a dismissible, user-facing alert for one local detection — separate from the mandatory
 * foreground-service notification (§5B: different channel, different notification id). */
interface DetectionNotifier {
    fun notify(detection: SessionDetection)
}
