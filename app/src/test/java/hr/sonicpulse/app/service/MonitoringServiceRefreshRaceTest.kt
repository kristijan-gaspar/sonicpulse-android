package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.FakeLocationProvider
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.data.location.LocationStartResult
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import hr.sonicpulse.app.repository.SubmissionCounters
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wires MonitoringLifecycleCoordinator + MonitoringRefreshCoordinator + a fake LocationProvider
 * together exactly the way MonitoringService.refreshLocation()/handleRefreshResult() do (Service
 * itself can't be unit-tested without an Android runtime — see MonitoringServiceStartupRaceTest
 * for the same pattern applied to the original startup race). Proves ACTION_REFRESH_LOCATION only
 * ever touches the location subscription, never the session/audio/counters, and that stale or
 * superseded callbacks can never resurrect a stopped session or double-report a failure.
 */
class MonitoringServiceRefreshRaceTest {

    private fun refreshLocation(
        lifecycleCoordinator: MonitoringLifecycleCoordinator,
        refreshCoordinator: MonitoringRefreshCoordinator,
        locationProvider: FakeLocationProvider,
        repository: FakeMonitoringStateRepository
    ) {
        val effect = refreshCoordinator.onRefreshRequested(lifecycleCoordinator.state)
        val generation = (effect as? MonitoringRefreshEffect.Begin)?.generation ?: return
        locationProvider.stop()
        locationProvider.start { result ->
            handleRefreshResult(generation, result, lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        }
    }

    private fun handleRefreshResult(
        generation: Long,
        result: LocationStartResult,
        lifecycleCoordinator: MonitoringLifecycleCoordinator,
        refreshCoordinator: MonitoringRefreshCoordinator,
        locationProvider: FakeLocationProvider,
        repository: FakeMonitoringStateRepository
    ) {
        if (!refreshCoordinator.isCurrent(generation, lifecycleCoordinator.state)) {
            return
        }
        when (result) {
            LocationStartResult.Started -> repository.locationRefreshSucceeded()
            LocationStartResult.Cancelled -> repository.locationRefreshFailed(LocationRefreshFailure.Failed)
            LocationStartResult.PermissionDenied ->
                repository.locationRefreshFailed(LocationRefreshFailure.PermissionDenied)
            LocationStartResult.LocationServicesDisabled -> {
                repository.updateLocationStatus(locationProvider.currentSnapshot, locationProvider.permissionLevel(), false)
                repository.locationRefreshFailed(LocationRefreshFailure.LocationServicesDisabled)
            }
            is LocationStartResult.Failed -> repository.locationRefreshFailed(LocationRefreshFailure.Failed)
        }
    }

    private data class Active(
        val lifecycleCoordinator: MonitoringLifecycleCoordinator,
        val refreshCoordinator: MonitoringRefreshCoordinator,
        val locationProvider: FakeLocationProvider
    )

    private fun activeSetup(): Active {
        val lifecycleCoordinator = MonitoringLifecycleCoordinator()
        val generation = (lifecycleCoordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        lifecycleCoordinator.onLocationStartResult(generation, LocationStartResult.Started)
        check(lifecycleCoordinator.state == MonitoringLifecycleState.ACTIVE)
        return Active(lifecycleCoordinator, MonitoringRefreshCoordinator(), FakeLocationProvider())
    }

    @Test
    fun `refresh while ACTIVE stops then starts the location provider exactly once`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        assertEquals(1, locationProvider.stopCallCount)
        assertEquals(1, locationProvider.startCallCount)
    }

    @Test
    fun `refresh while ACTIVE does not touch session detections or submission counters`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        val target = SessionDetection(UUID.randomUUID(), -10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)
        repository.localDetectionOccurred(target)

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        locationProvider.completeStart(LocationStartResult.Started)

        assertTrue(repository.state.value.isMonitoring)
        assertEquals(listOf(target), repository.state.value.sessionDetections)
        assertEquals(SubmissionCounters(), repository.state.value.submissionCounters)
    }

    @Test
    fun `refresh while IDLE is ignored, never touching the location provider`() {
        val lifecycleCoordinator = MonitoringLifecycleCoordinator()
        val refreshCoordinator = MonitoringRefreshCoordinator()
        val locationProvider = FakeLocationProvider()
        val repository = FakeMonitoringStateRepository()

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        assertEquals(0, locationProvider.stopCallCount)
        assertEquals(0, locationProvider.startCallCount)
    }

    @Test
    fun `Stop invalidates a pending refresh so its eventual Started callback changes nothing`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        // Mirrors MonitoringService.stopMonitoringAndService(): lifecycle -> IDLE, then invalidate().
        lifecycleCoordinator.onStopOrDestroy()
        refreshCoordinator.invalidate()
        val stateAfterStop = repository.state.value

        locationProvider.completeStart(LocationStartResult.Started)

        assertEquals(stateAfterStop, repository.state.value)
    }

    @Test
    fun `a newer refresh invalidates the older callback, which reports nothing once it resolves`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        // A second refresh begins before the first resolves: locationProvider.stop() cancels the
        // first attempt synchronously (resolving its callback with Cancelled) before registering
        // the second — that Cancelled is already filtered out by generation mismatch below.
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.PermissionDenied)

        assertEquals(listOf(LocationRefreshFailure.PermissionDenied), repository.locationRefreshFailures)
    }

    @Test
    fun `a stale Started result after Stop has no effect on repository state`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        lifecycleCoordinator.onStopOrDestroy()
        refreshCoordinator.invalidate()
        repository.monitoringStopped()
        val stateAfterStop = repository.state.value

        locationProvider.completeStart(LocationStartResult.Started)

        assertEquals(stateAfterStop, repository.state.value)
        assertTrue(repository.locationRefreshFailures.isEmpty())
    }

    @Test
    fun `PermissionDenied during refresh is nonfatal — isMonitoring stays true`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.PermissionDenied)

        assertTrue(repository.state.value.isMonitoring)
        assertEquals(LocationRefreshFailure.PermissionDenied, repository.state.value.locationRefreshError)
    }

    @Test
    fun `LocationServicesDisabled during refresh is nonfatal and marks location status unavailable`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.LocationServicesDisabled)

        assertTrue(repository.state.value.isMonitoring)
        assertFalse(repository.state.value.locationServicesEnabled)
        assertEquals(LocationRefreshFailure.LocationServicesDisabled, repository.state.value.locationRefreshError)
    }

    @Test
    fun `Failed during refresh is nonfatal and never exposes the raw exception to the repository`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.Failed(RuntimeException("boom")))

        assertTrue(repository.state.value.isMonitoring)
        assertEquals(LocationRefreshFailure.Failed, repository.state.value.locationRefreshError)
    }

    @Test
    fun `a current-generation Cancelled while ACTIVE publishes LocationRefreshFailure Failed`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.Cancelled)

        assertEquals(LocationRefreshFailure.Failed, repository.state.value.locationRefreshError)
    }

    @Test
    fun `a current-generation Cancelled keeps isMonitoring true`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        locationProvider.completeStart(LocationStartResult.Cancelled)

        assertTrue(repository.state.value.isMonitoring)
    }

    @Test
    fun `a stale Cancelled after Stop produces no error`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        // Mirrors MonitoringService.stopMonitoringAndService(): lifecycle -> IDLE, then invalidate(),
        // then tearDownCaptureAndLocation()'s locationProvider.stop() resolving the pending attempt.
        lifecycleCoordinator.onStopOrDestroy()
        refreshCoordinator.invalidate()

        locationProvider.completeStart(LocationStartResult.Cancelled)

        assertNull(repository.state.value.locationRefreshError)
        assertTrue(repository.locationRefreshFailures.isEmpty())
    }

    @Test
    fun `a stale Cancelled after a newer refresh produces no error`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        // The second refreshLocation() call's own locationProvider.stop() resolves the first
        // attempt's callback with Cancelled synchronously, before the second attempt registers.
        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)

        assertNull(repository.state.value.locationRefreshError)
        assertTrue(repository.locationRefreshFailures.isEmpty())
    }

    @Test
    fun `Started clears a previous locationRefreshError`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        repository.locationRefreshFailed(LocationRefreshFailure.PermissionDenied)
        check(repository.state.value.locationRefreshError == LocationRefreshFailure.PermissionDenied)

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        locationProvider.completeStart(LocationStartResult.Started)

        assertNull(repository.state.value.locationRefreshError)
    }

    @Test
    fun `Started does not bump errorEventId`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        val eventIdBefore = repository.state.value.errorEventId

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        locationProvider.completeStart(LocationStartResult.Started)

        assertEquals(eventIdBefore, repository.state.value.errorEventId)
    }

    @Test
    fun `Started keeps monitoring active and preserves detections and submission counters — no session restart`() {
        val (lifecycleCoordinator, refreshCoordinator, locationProvider) = activeSetup()
        val repository = FakeMonitoringStateRepository()
        repository.monitoringStarted()
        val target = SessionDetection(UUID.randomUUID(), -10.0, Instant.EPOCH, LocationSnapshot.NoFixYet)
        repository.localDetectionOccurred(target)
        repository.submissionSucceeded(target.localEventId)

        refreshLocation(lifecycleCoordinator, refreshCoordinator, locationProvider, repository)
        locationProvider.completeStart(LocationStartResult.Started)

        // monitoringStarted() resets MonitoringState entirely — if refresh had called it again,
        // both sessionDetections and submissionCounters would be back to empty/zero.
        assertTrue(repository.state.value.isMonitoring)
        assertEquals(listOf(target.localEventId), repository.state.value.sessionDetections.map { it.localEventId })
        assertEquals(1, repository.state.value.submissionCounters.submissionSucceeded)
    }
}
