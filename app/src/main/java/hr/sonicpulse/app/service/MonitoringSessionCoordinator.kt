package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.repository.MonitoringStateRepository

/**
 * Owns the exact sequencing of MonitoringStateRepository transitions around a capture
 * session's start and end — pulled out of MonitoringService so these invariants are covered
 * by plain JVM tests, since Service itself can't be unit-tested without an Android runtime.
 *
 * - monitoringStarted() always runs before [startCapture] is invoked, and monitoringFailed()
 *   runs synchronously inside the wrapped onError — before forwarding to onCaptureError — so
 *   a capture failure (even one reported synchronously, before startCapture's own call
 *   returns) always has the last word on repository state, regardless of which thread or
 *   dispatcher the caller's own cleanup eventually runs on.
 * - endSession() only publishes monitoringStopped() if the session was still active — an
 *   already-stopped or already-failed session must not get a redundant "stopped" transition
 *   that could mask a captureError with a plain idle state.
 */
class MonitoringSessionCoordinator(
    private val monitoringStateRepository: MonitoringStateRepository
) {
    fun startSession(
        startCapture: (onBlock: (ShortArray) -> Unit, onError: (AudioCaptureError) -> Unit) -> Unit,
        onBlock: (ShortArray) -> Unit,
        onCaptureError: (AudioCaptureError) -> Unit
    ) {
        monitoringStateRepository.monitoringStarted()
        startCapture(onBlock) { error ->
            monitoringStateRepository.monitoringFailed(error)
            onCaptureError(error)
        }
    }

    fun endSession(wasActiveBeforeTeardown: Boolean) {
        if (wasActiveBeforeTeardown) {
            monitoringStateRepository.monitoringStopped()
        }
    }
}
