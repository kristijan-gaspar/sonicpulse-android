package hr.sonicpulse.app.data.remote

import hr.sonicpulse.app.data.datastore.FakeInstallationIdRepository
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.SubmissionStatus
import hr.sonicpulse.app.repository.DefaultMonitoringStateRepository
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionSubmitterTest {

    private val installationId = "22222222-2222-2222-2222-222222222222"

    private fun detection(location: LocationSnapshot) = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -8.0,
        peakTimeClient = Instant.parse("2026-08-03T10:00:00Z"),
        location = location
    )

    private fun submitterWith(
        api: FakeDetectionApi,
        stateRepository: DefaultMonitoringStateRepository = DefaultMonitoringStateRepository(),
        installationIdRepository: FakeInstallationIdRepository = FakeInstallationIdRepository(installationId)
    ) = DetectionSubmitter(
        detectionApi = api,
        installationIdRepository = installationIdRepository,
        monitoringStateRepository = stateRepository,
        submissionLogger = FakeSubmissionLogger()
    ) to stateRepository

    @Test
    fun `valid location and 201 response marks the detection as sent`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(201) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(latitude = 45.8, longitude = 16.0, accuracyMeters = 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionSucceeded)
        val sentRequest = api.requests.single()
        assertEquals(installationId, sentRequest.deviceId)
        assertEquals(45.8, sentRequest.latitude, 0.0)
        assertEquals(16.0, sentRequest.longitude, 0.0)
        assertEquals(8.0, sentRequest.gpsAccuracy, 0.0)
        assertEquals("2026-08-03T10:00:00Z", sentRequest.peakTimeClient)
    }

    @Test
    fun `NoFixYet location drops before attempting network`() = runTest {
        val api = FakeDetectionApi()
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.NoFixYet)
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertTrue(api.requests.isEmpty())
        assertEquals(
            SubmissionFailureReason.NoLocation,
            (stateRepository.state.value.sessionDetections.single().submissionStatus as SubmissionStatus.Failed).reason
        )
    }

    @Test
    fun `Invalid location drops before attempting network as NoLocation`() = runTest {
        val api = FakeDetectionApi()
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Invalid)
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertTrue(api.requests.isEmpty())
        assertEquals(
            SubmissionFailureReason.NoLocation,
            (stateRepository.state.value.sessionDetections.single().submissionStatus as SubmissionStatus.Failed).reason
        )
    }

    @Test
    fun `Stale location drops before attempting network`() = runTest {
        val api = FakeDetectionApi()
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Stale(ageMillis = 999_999))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertTrue(api.requests.isEmpty())
        assertEquals(1, stateRepository.state.value.submissionCounters.droppedStaleLocation)
    }

    @Test
    fun `Inaccurate location drops before attempting network`() = runTest {
        val api = FakeDetectionApi()
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Inaccurate(accuracyMeters = 500f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertTrue(api.requests.isEmpty())
        assertEquals(1, stateRepository.state.value.submissionCounters.droppedInaccurateLocation)
    }

    @Test
    fun `a network IOException maps to NetworkError, distinct from a local-storage failure`() = runTest {
        val api = FakeDetectionApi().apply { throwOnSubmit = IOException("timeout") }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.droppedNetwork)
        assertEquals(0, stateRepository.state.value.submissionCounters.droppedLocalStorage)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `an IOException reading the installation id maps to LocalStorageError and performs no HTTP request`() = runTest {
        val api = FakeDetectionApi()
        val (submitter, stateRepository) = submitterWith(
            api = api,
            installationIdRepository = FakeInstallationIdRepository().apply {
                throwOnGetOrCreate = IOException("corrupted preferences file")
            }
        )
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertTrue(api.requests.isEmpty())
        assertEquals(1, stateRepository.state.value.submissionCounters.droppedLocalStorage)
        assertEquals(0, stateRepository.state.value.submissionCounters.droppedNetwork)
    }

    @Test
    fun `400 response maps to BAD_REQUEST`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(400) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedBadRequest)
    }

    @Test
    fun `401 response maps to UNAUTHORIZED and sets serverConfigurationError`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(401) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedUnauthorized)
        assertTrue(stateRepository.state.value.serverConfigurationError)
    }

    @Test
    fun `429 response maps to RATE_LIMITED`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(429, retryAfterHeader = "30") }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionRateLimited)
    }

    @Test
    fun `404 response maps to CLIENT_ERROR`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(404) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedClient)
    }

    @Test
    fun `500 response maps to SERVER_ERROR`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(500) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedServer)
    }

    @Test
    fun `403 response also maps to Unauthorized`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(403) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedUnauthorized)
        assertTrue(stateRepository.state.value.serverConfigurationError)
    }

    @Test
    fun `an unexpected 1xx or 3xx status maps to UnexpectedHttpStatus`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(302) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(
            SubmissionStatus.Failed(SubmissionFailureReason.UnexpectedHttpStatus(302)),
            stateRepository.state.value.sessionDetections.single().submissionStatus
        )
        assertEquals(1, stateRepository.state.value.submissionCounters.submissionFailedUnexpected)
    }

    @Test
    fun `a 200 response is also successful, not only 201`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(200) }
        val (submitter, stateRepository) = submitterWith(api)
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        stateRepository.localDetectionOccurred(target)

        submitter.submit(target)

        assertEquals(1, stateRepository.state.value.submissionCounters.submissionSucceeded)
    }
}
