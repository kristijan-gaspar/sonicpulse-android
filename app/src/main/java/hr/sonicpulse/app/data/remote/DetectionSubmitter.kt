package hr.sonicpulse.app.data.remote

import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.repository.MonitoringStateRepository
import java.io.IOException
import javax.inject.Inject

/**
 * Attempts to submit one locally detected event to the backend, per plan §2.9's outcome table.
 * A [SessionDetection] can only ever carry a [hr.sonicpulse.app.data.location.LocationSnapshot.Valid]
 * location (enforced by its own type), so no/stale/inaccurate location is never a state this
 * function has to handle — that's decided earlier, before a SessionDetection is even created.
 *
 * Two distinct I/O failure domains are kept separate: a failure reading the installation id from
 * DataStore never reaches the network at all ([SubmissionFailureReason.LocalStorageError]); a
 * failure from the actual HTTP call is [SubmissionFailureReason.NetworkError]. Only [IOException]
 * is caught in either boundary — coroutine cancellation and any other [Throwable] propagate.
 */
class DetectionSubmitter @Inject constructor(
    private val detectionApi: DetectionApi,
    private val installationIdRepository: InstallationIdRepository,
    private val monitoringStateRepository: MonitoringStateRepository,
    private val submissionLogger: SubmissionLogger
) {

    suspend fun submit(detection: SessionDetection) {
        val location = detection.location

        val installationId = try {
            installationIdRepository.getOrCreate()
        } catch (e: IOException) {
            submissionLogger.localStorageError()
            monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.LocalStorageError)
            return
        }

        val dto = DetectionRequestDto(
            deviceId = installationId,
            peakDbfs = detection.peakDbfs,
            latitude = location.latitude,
            longitude = location.longitude,
            gpsAccuracy = location.accuracyMeters.toDouble(),
            peakTimeClient = detection.peakTimeClient.toString()
        )

        val outcome = try {
            val response = detectionApi.submitDetection(dto)
            SubmissionOutcomeMapper.map(response.code(), response.headers()["Retry-After"])
        } catch (e: IOException) {
            submissionLogger.networkError()
            monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.NetworkError)
            return
        }

        logOutcome(outcome)
        applyOutcome(detection, outcome)
    }

    /** Diagnostics-only logging per plan §2.9 — never logs the API key, installationId, coordinates or a raw error body. */
    private fun logOutcome(outcome: SubmissionOutcome) {
        when (outcome) {
            SubmissionOutcome.Success -> Unit
            SubmissionOutcome.BadRequest -> submissionLogger.badRequest()
            SubmissionOutcome.Unauthorized -> submissionLogger.unauthorized()
            is SubmissionOutcome.RateLimited -> submissionLogger.rateLimited(outcome.retryAfterSeconds)
            is SubmissionOutcome.ClientError -> submissionLogger.clientError(outcome.httpCode)
            is SubmissionOutcome.ServerError -> submissionLogger.serverError(outcome.httpCode)
            is SubmissionOutcome.UnexpectedHttpStatus -> submissionLogger.unexpectedHttpStatus(outcome.httpCode)
        }
    }

    private fun applyOutcome(detection: SessionDetection, outcome: SubmissionOutcome) {
        when (outcome) {
            SubmissionOutcome.Success ->
                monitoringStateRepository.submissionSucceeded(detection.localEventId)
            SubmissionOutcome.BadRequest ->
                fail(detection, SubmissionFailureReason.BadRequest)
            SubmissionOutcome.Unauthorized ->
                fail(detection, SubmissionFailureReason.Unauthorized)
            is SubmissionOutcome.RateLimited ->
                fail(detection, SubmissionFailureReason.RateLimited(outcome.retryAfterSeconds))
            is SubmissionOutcome.ClientError ->
                fail(detection, SubmissionFailureReason.ClientError(outcome.httpCode))
            is SubmissionOutcome.ServerError ->
                fail(detection, SubmissionFailureReason.ServerError(outcome.httpCode))
            is SubmissionOutcome.UnexpectedHttpStatus ->
                fail(detection, SubmissionFailureReason.UnexpectedHttpStatus(outcome.httpCode))
        }
    }

    private fun fail(detection: SessionDetection, reason: SubmissionFailureReason) =
        monitoringStateRepository.submissionFailed(detection.localEventId, reason)
}
