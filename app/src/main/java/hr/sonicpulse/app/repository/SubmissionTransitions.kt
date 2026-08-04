package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
import hr.sonicpulse.app.service.LocationRefreshFailure
import hr.sonicpulse.app.service.MonitoringStartupFailure
import java.util.UUID

/**
 * Pure state-transition rules shared by [DefaultMonitoringStateRepository] and
 * [hr.sonicpulse.app.repository.FakeMonitoringStateRepository] (plan §2.9's local-vs-sent model):
 * transitions are first-wins (`Pending` → `Sent`/`Failed`, terminal states never change again),
 * and an unknown or already-evicted `localEventId` (past the 100-item retention limit) is a no-op.
 * No Android dependency — safe to unit-test directly.
 */
internal object SubmissionTransitions {

    fun applySuccess(state: MonitoringState, localEventId: UUID): MonitoringState {
        if (!isRetainedAndPending(state, localEventId)) return state
        return state.copy(
            sessionDetections = replaceStatus(state.sessionDetections, localEventId, SubmissionStatus.Sent),
            submissionCounters = state.submissionCounters.copy(
                submissionSucceeded = state.submissionCounters.submissionSucceeded + 1
            )
        )
    }

    fun applyFailure(state: MonitoringState, localEventId: UUID, reason: SubmissionFailureReason): MonitoringState {
        if (!isRetainedAndPending(state, localEventId)) return state
        return state.copy(
            sessionDetections = replaceStatus(state.sessionDetections, localEventId, SubmissionStatus.Failed(reason)),
            submissionCounters = incrementCounter(state.submissionCounters, reason),
            serverConfigurationError = state.serverConfigurationError || reason == SubmissionFailureReason.Unauthorized
        )
    }

    /** [SubmissionCounters.permissionFailures] only counts the two startup failures that are
     * actually permission-related — [MonitoringStartupFailure.LocationServicesDisabled] and the
     * two `*StartFailed` variants are unrelated failure modes, never counted here. */
    fun incrementPermissionFailuresIfApplicable(
        counters: SubmissionCounters,
        failure: MonitoringStartupFailure
    ): SubmissionCounters = when (failure) {
        MonitoringStartupFailure.MicrophonePermissionDenied,
        MonitoringStartupFailure.LocationPermissionDenied ->
            counters.copy(permissionFailures = counters.permissionFailures + 1)
        MonitoringStartupFailure.LocationServicesDisabled,
        is MonitoringStartupFailure.LocationStartFailed,
        is MonitoringStartupFailure.ForegroundStartFailed -> counters
    }

    fun incrementPermissionFailuresIfApplicable(
        counters: SubmissionCounters,
        failure: LocationRefreshFailure
    ): SubmissionCounters = if (failure is LocationRefreshFailure.PermissionDenied) {
        counters.copy(permissionFailures = counters.permissionFailures + 1)
    } else {
        counters
    }

    /** Gives every retained Pending detection a terminal Failed(Cancelled) result; idempotent. */
    fun cancelPending(state: MonitoringState): MonitoringState =
        state.sessionDetections
            .filter { it.submissionStatus == SubmissionStatus.Pending }
            .fold(state) { acc, detection -> applyFailure(acc, detection.localEventId, SubmissionFailureReason.Cancelled) }

    private fun isRetainedAndPending(state: MonitoringState, localEventId: UUID): Boolean {
        val current = state.sessionDetections.find { it.localEventId == localEventId } ?: return false
        return current.submissionStatus == SubmissionStatus.Pending
    }

    private fun replaceStatus(
        detections: List<SessionDetection>,
        localEventId: UUID,
        status: SubmissionStatus
    ) = detections.map { if (it.localEventId == localEventId) it.copy(submissionStatus = status) else it }

    private fun incrementCounter(counters: SubmissionCounters, reason: SubmissionFailureReason): SubmissionCounters =
        when (reason) {
            SubmissionFailureReason.NoLocation -> counters.copy(droppedNoLocation = counters.droppedNoLocation + 1)
            SubmissionFailureReason.StaleLocation -> counters.copy(droppedStaleLocation = counters.droppedStaleLocation + 1)
            SubmissionFailureReason.InaccurateLocation ->
                counters.copy(droppedInaccurateLocation = counters.droppedInaccurateLocation + 1)
            SubmissionFailureReason.LocalStorageError -> counters.copy(droppedLocalStorage = counters.droppedLocalStorage + 1)
            SubmissionFailureReason.NetworkError -> counters.copy(droppedNetwork = counters.droppedNetwork + 1)
            SubmissionFailureReason.Cancelled -> counters.copy(cancelled = counters.cancelled + 1)
            SubmissionFailureReason.BadRequest ->
                counters.copy(submissionFailedBadRequest = counters.submissionFailedBadRequest + 1)
            SubmissionFailureReason.Unauthorized ->
                counters.copy(submissionFailedUnauthorized = counters.submissionFailedUnauthorized + 1)
            is SubmissionFailureReason.RateLimited -> counters.copy(submissionRateLimited = counters.submissionRateLimited + 1)
            is SubmissionFailureReason.ClientError -> counters.copy(submissionFailedClient = counters.submissionFailedClient + 1)
            is SubmissionFailureReason.ServerError -> counters.copy(submissionFailedServer = counters.submissionFailedServer + 1)
            is SubmissionFailureReason.UnexpectedHttpStatus ->
                counters.copy(submissionFailedUnexpected = counters.submissionFailedUnexpected + 1)
        }
}
