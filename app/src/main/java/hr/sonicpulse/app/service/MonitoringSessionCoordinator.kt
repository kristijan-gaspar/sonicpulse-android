package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.repository.MonitoringStateRepository

/**
 * Owns the exact sequencing of MonitoringStateRepository transitions around a capture
 * session's start and end — pulled out of MonitoringService so these invariants are covered
 * by plain JVM tests, since Service itself can't be unit-tested without an Android runtime.
 *
 * - monitoringStarted() always runs before [startCapture] is invoked.
 * - This coordinator has no opinion on whether a capture error still belongs to the current
 *   session, and never touches [MonitoringStateRepository] for one — a reported error is only
 *   ever forwarded to [onCaptureError], unchanged. The session-identity check and the
 *   corresponding `monitoringFailed()` publish happen together, on the same dispatcher, in
 *   `MonitoringService.handleCaptureError` — never split across two separate operations here
 *   that an explicit Stop landing in between could observe half-applied.
 * - endSession() only publishes monitoringStopped() if the session was still active — an
 *   already-stopped or already-failed session must not get a redundant "stopped" transition
 *   that could mask a captureError with a plain idle state.
 */
class MonitoringSessionCoordinator(
    private val monitoringStateRepository: MonitoringStateRepository
) {
    fun startSession(
        startCapture: (
            onBlock: (ShortArray) -> Unit,
            onError: (AudioCaptureError) -> Unit
        ) -> Unit,
        onBlock: (ShortArray) -> Unit,
        onCaptureError: (AudioCaptureError) -> Unit
    ) {
        monitoringStateRepository.monitoringStarted()

        startCapture(onBlock, onCaptureError)
    }

    fun endSession(wasActiveBeforeTeardown: Boolean) {
        if (wasActiveBeforeTeardown) {
            monitoringStateRepository.monitoringStopped()
        }
    }
}
