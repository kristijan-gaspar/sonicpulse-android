package hr.sonicpulse.app.data.remote

import android.util.Log
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.repository.MonitoringStateRepository
import java.io.IOException
import javax.inject.Inject
import retrofit2.Response

/**
 * Attempts to submit one locally detected event to the backend, per plan §2.9's outcome table.
 * Local-drop cases (no/stale/inaccurate location) are decided before any network attempt.
 */
class DetectionSubmitter @Inject constructor(
    private val detectionApi: DetectionApi,
    private val installationIdRepository: InstallationIdRepository,
    private val monitoringStateRepository: MonitoringStateRepository
) {

    private companion object {
        const val TAG = "DetectionSubmitter"
    }

    suspend fun submit(detection: SessionDetection) {
        val dropReason = localDropReasonFor(detection.location)
        if (dropReason != null) {
            monitoringStateRepository.submissionFailed(detection.localEventId, dropReason)
            return
        }

        val location = detection.location as LocationSnapshot.Valid

        val outcome = try {
            val dto = DetectionRequestDto(
                deviceId = installationIdRepository.getOrCreate(),
                peakDbfs = detection.peakDbfs,
                latitude = location.latitude,
                longitude = location.longitude,
                gpsAccuracy = location.accuracyMeters.toDouble(),
                peakTimeClient = detection.peakTimeClient.toString()
            )
            val response = detectionApi.submitDetection(dto)
            SubmissionOutcomeMapper.map(response.code(), response.headers()["Retry-After"]).also {
                logOutcome(it, response)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Detection submission failed due to a network error")
            SubmissionOutcome.NetworkError
        }

        applyOutcome(detection, outcome)
    }

    /** Diagnostics-only logging per plan §2.9 — never logs the API key, installationId, or coordinates. */
    private fun logOutcome(outcome: SubmissionOutcome, response: Response<Unit>) {
        when (outcome) {
            SubmissionOutcome.BadRequest -> {
                val body = if (BuildConfig.DEBUG) response.errorBody()?.runCatching { string() }?.getOrNull() else null
                Log.e(TAG, "Detection submission rejected as malformed (400)" + (body?.let { ": $it" } ?: ""))
            }
            is SubmissionOutcome.ServerError ->
                Log.w(TAG, "Detection submission failed with a server error (${outcome.httpCode})")
            is SubmissionOutcome.RateLimited ->
                Log.i(TAG, "Detection submission rate-limited (retryAfterSeconds=${outcome.retryAfterSeconds})")
            else -> Unit
        }
    }

    private fun applyOutcome(detection: SessionDetection, outcome: SubmissionOutcome) {
        when (outcome) {
            SubmissionOutcome.Success ->
                monitoringStateRepository.submissionSucceeded(detection.localEventId)
            SubmissionOutcome.BadRequest ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.BAD_REQUEST)
            SubmissionOutcome.Unauthorized ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.UNAUTHORIZED)
            is SubmissionOutcome.RateLimited ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.RATE_LIMITED)
            is SubmissionOutcome.ClientError ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.CLIENT_ERROR)
            is SubmissionOutcome.ServerError ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.SERVER_ERROR)
            SubmissionOutcome.NetworkError ->
                monitoringStateRepository.submissionFailed(detection.localEventId, SubmissionFailureReason.NETWORK_ERROR)
        }
    }

    /** NoFixYet/Invalid both mean "nothing usable to send" — the plan's table only names one such row. */
    private fun localDropReasonFor(location: LocationSnapshot): SubmissionFailureReason? = when (location) {
        LocationSnapshot.NoFixYet, LocationSnapshot.Invalid -> SubmissionFailureReason.NO_LOCATION
        is LocationSnapshot.Stale -> SubmissionFailureReason.STALE_LOCATION
        is LocationSnapshot.Inaccurate -> SubmissionFailureReason.INACCURATE_LOCATION
        is LocationSnapshot.Valid -> null
    }
}
