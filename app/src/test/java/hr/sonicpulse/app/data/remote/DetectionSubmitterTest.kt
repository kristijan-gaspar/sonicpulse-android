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

private const val INSTALLATION_ID = "22222222-2222-2222-2222-222222222222"

/** Wires a [DetectionSubmitter] to fakes/a real state repository so a test can assert on all three at once. */
private class DetectionSubmitterFixture(
    val api: FakeDetectionApi,
    val installationIdRepository: FakeInstallationIdRepository = FakeInstallationIdRepository(INSTALLATION_ID),
    val stateRepository: DefaultMonitoringStateRepository = DefaultMonitoringStateRepository(),
    val logger: FakeSubmissionLogger = FakeSubmissionLogger()
) {
    val submitter = DetectionSubmitter(
        detectionApi = api,
        installationIdRepository = installationIdRepository,
        monitoringStateRepository = stateRepository,
        submissionLogger = logger
    )

    fun statusOf(detection: SessionDetection): SubmissionStatus =
        stateRepository.state.value.sessionDetections.single { it.localEventId == detection.localEventId }.submissionStatus
}

class DetectionSubmitterTest {

    private fun detection(location: LocationSnapshot.Valid) = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -8.0,
        peakTimeClient = Instant.parse("2026-08-03T10:00:00Z"),
        location = location
    )

    private fun fixture(
        api: FakeDetectionApi,
        installationIdRepository: FakeInstallationIdRepository = FakeInstallationIdRepository(INSTALLATION_ID)
    ) = DetectionSubmitterFixture(api, installationIdRepository)

    @Test
    fun `a valid detection is sent as the expected request DTO`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(201) }
        val f = fixture(api)
        val target = detection(LocationSnapshot.Valid(latitude = 45.8, longitude = 16.0, accuracyMeters = 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        val sentRequest = api.requests.single()
        assertEquals(INSTALLATION_ID, sentRequest.deviceId)
        assertEquals(45.8, sentRequest.latitude, 0.0)
        assertEquals(16.0, sentRequest.longitude, 0.0)
        assertEquals(8.0, sentRequest.gpsAccuracy, 0.0)
        assertEquals("2026-08-03T10:00:00Z", sentRequest.peakTimeClient)
    }

    @Test
    fun `every 2xx response marks the detection Sent with no logger event`() = runTest {
        listOf(200, 201, 202, 204).forEach { code ->
            val f = fixture(FakeDetectionApi { fakeHttpResponse(code) })
            val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
            f.stateRepository.localDetectionOccurred(target)

            f.submitter.submit(target)

            assertEquals("code $code", SubmissionStatus.Sent, f.statusOf(target))
            assertEquals("code $code", 1, f.stateRepository.state.value.submissionCounters.submissionSucceeded)
            assertTrue("code $code", f.logger.events.isEmpty())
        }
    }

    @Test
    fun `every submitted DTO carries latitude, longitude and strictly positive gpsAccuracy`() = runTest {
        val api = FakeDetectionApi { fakeHttpResponse(201) }
        val f = fixture(api)
        val target = detection(LocationSnapshot.Valid(latitude = 45.8, longitude = 16.0, accuracyMeters = 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        val sentRequest = api.requests.single()
        assertEquals(45.8, sentRequest.latitude, 0.0)
        assertEquals(16.0, sentRequest.longitude, 0.0)
        assertTrue(sentRequest.gpsAccuracy > 0.0)
    }

    @Test
    fun `a network IOException fails with NetworkError and logs networkError`() = runTest {
        val f = fixture(FakeDetectionApi().apply { throwOnSubmit = IOException("timeout") })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.NetworkError), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.droppedNetwork)
        assertEquals(0, f.stateRepository.state.value.submissionCounters.droppedLocalStorage)
        assertEquals(1, f.api.requests.size)
        assertEquals(listOf("networkError"), f.logger.events)
    }

    @Test
    fun `an installation-id IOException fails with LocalStorageError, no HTTP request, and logs localStorageError`() = runTest {
        val f = fixture(
            api = FakeDetectionApi(),
            installationIdRepository = FakeInstallationIdRepository().apply {
                throwOnGetOrCreate = IOException("corrupted preferences file")
            }
        )
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertTrue(f.api.requests.isEmpty())
        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.LocalStorageError), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.droppedLocalStorage)
        assertEquals(0, f.stateRepository.state.value.submissionCounters.droppedNetwork)
        assertEquals(listOf("localStorageError"), f.logger.events)
    }

    @Test
    fun `400 fails with BadRequest and logs badRequest`() = runTest {
        val f = fixture(FakeDetectionApi { fakeHttpResponse(400) })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.BadRequest), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.submissionFailedBadRequest)
        assertEquals(listOf("badRequest"), f.logger.events)
    }

    @Test
    fun `401 and 403 both fail with Unauthorized, set serverConfigurationError, and log unauthorized`() = runTest {
        listOf(401, 403).forEach { code ->
            val f = fixture(FakeDetectionApi { fakeHttpResponse(code) })
            val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
            f.stateRepository.localDetectionOccurred(target)

            f.submitter.submit(target)

            assertEquals("code $code", SubmissionStatus.Failed(SubmissionFailureReason.Unauthorized), f.statusOf(target))
            assertEquals("code $code", 1, f.stateRepository.state.value.submissionCounters.submissionFailedUnauthorized)
            assertTrue("code $code", f.stateRepository.state.value.serverConfigurationError)
            assertEquals("code $code", listOf("unauthorized"), f.logger.events)
        }
    }

    @Test
    fun `429 with Retry-After 30 fails with RateLimited(30) and logs rateLimited(30)`() = runTest {
        val f = fixture(FakeDetectionApi { fakeHttpResponse(429, retryAfterHeader = "30") })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.RateLimited(30L)), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.submissionRateLimited)
        assertEquals(listOf("rateLimited(30)"), f.logger.events)
    }

    @Test
    fun `404 fails with ClientError(404) and logs clientError(404)`() = runTest {
        val f = fixture(FakeDetectionApi { fakeHttpResponse(404) })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.ClientError(404)), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.submissionFailedClient)
        assertEquals(listOf("clientError(404)"), f.logger.events)
    }

    @Test
    fun `500 fails with ServerError(500) and logs serverError(500)`() = runTest {
        val f = fixture(FakeDetectionApi { fakeHttpResponse(500) })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.ServerError(500)), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.submissionFailedServer)
        assertEquals(listOf("serverError(500)"), f.logger.events)
    }

    @Test
    fun `302 fails with UnexpectedHttpStatus(302) and logs unexpectedHttpStatus(302)`() = runTest {
        val f = fixture(FakeDetectionApi { fakeHttpResponse(302) })
        val target = detection(LocationSnapshot.Valid(45.8, 16.0, 8.0f))
        f.stateRepository.localDetectionOccurred(target)

        f.submitter.submit(target)

        assertEquals(SubmissionStatus.Failed(SubmissionFailureReason.UnexpectedHttpStatus(302)), f.statusOf(target))
        assertEquals(1, f.stateRepository.state.value.submissionCounters.submissionFailedUnexpected)
        assertEquals(listOf("unexpectedHttpStatus(302)"), f.logger.events)
    }
}
