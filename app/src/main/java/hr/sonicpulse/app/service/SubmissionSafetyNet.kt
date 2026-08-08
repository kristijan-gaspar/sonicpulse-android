package hr.sonicpulse.app.service

import android.util.Log
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.repository.MonitoringStateRepository
import kotlinx.coroutines.CancellationException

private const val TAG = "MonitoringService"

/**
 * Root-coroutine safety net around one submission attempt. [hr.sonicpulse.app.data.remote.DetectionSubmitter]'s
 * own KDoc documents that it deliberately lets any exception other than IOException propagate out
 * of `submit()` — that boundary is the network/storage layer's, not this one's. This is the
 * boundary that must never let an unexpected, non-cancellation [Exception] escape uncaught into
 * [attemptSubmit]'s caller (a launch on MonitoringService's serviceScope, an application-level
 * root coroutine with no installed CoroutineExceptionHandler) and crash the process.
 * [CancellationException] is rethrown untouched; any other [Exception] becomes a terminal
 * [SubmissionFailureReason.UnexpectedError], so [detection] can never be silently stuck Pending
 * forever because of a bug elsewhere in the submission path.
 */
internal suspend fun submitDetectionSafely(
    monitoringStateRepository: MonitoringStateRepository,
    detection: SessionDetection,
    logUnexpected: (Throwable) -> Unit = { Log.e(TAG, "Unexpected failure submitting detection", it) },
    attemptSubmit: suspend () -> Unit
) {
    try {
        attemptSubmit()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logUnexpected(e)
        monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.UnexpectedError)
    }
}
