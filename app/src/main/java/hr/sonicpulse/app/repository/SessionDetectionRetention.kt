package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.SessionDetection

/** Shared retention rule (plan §2.6): only the latest [MAX_SESSION_DETECTIONS] detections are kept in memory. */
internal object SessionDetectionRetention {
    const val MAX_SESSION_DETECTIONS = 100

    fun append(detections: List<SessionDetection>, detection: SessionDetection): List<SessionDetection> =
        (detections + detection).takeLast(MAX_SESSION_DETECTIONS)
}
