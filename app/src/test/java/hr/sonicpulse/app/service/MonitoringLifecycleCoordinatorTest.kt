package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationStartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringLifecycleCoordinatorTest {

    @Test
    fun `onActionStart from IDLE transitions to STARTING and returns a generation to start location with`() {
        val coordinator = MonitoringLifecycleCoordinator()

        val effect = coordinator.onActionStart()

        assertTrue(effect is MonitoringLifecycleEffect.StartLocation)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }

    @Test
    fun `onActionStart while STARTING is ignored`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()

        val effect = coordinator.onActionStart()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }

    @Test
    fun `onActionStart while ACTIVE is ignored`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        val effect = coordinator.onActionStart()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.ACTIVE, coordinator.state)
    }

    @Test
    fun `Started for the current generation transitions to ACTIVE and requests audio capture`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        assertEquals(MonitoringLifecycleEffect.StartAudioCapture, effect)
        assertEquals(MonitoringLifecycleState.ACTIVE, coordinator.state)
    }

    @Test
    fun `PermissionDenied while STARTING returns to IDLE and reports LocationPermissionDenied`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.PermissionDenied)

        assertEquals(
            MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationPermissionDenied),
            effect
        )
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `LocationServicesDisabled while STARTING returns to IDLE and reports LocationServicesDisabled`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.LocationServicesDisabled)

        assertEquals(
            MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationServicesDisabled),
            effect
        )
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `Failed while STARTING returns to IDLE and reports LocationStartFailed with the cause`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        val cause = RuntimeException("boom")

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Failed(cause))

        assertEquals(
            MonitoringLifecycleEffect.ReportStartupFailure(MonitoringStartupFailure.LocationStartFailed(cause)),
            effect
        )
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `a Cancelled result for an already-invalidated generation is ignored and does not resurrect state`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onStopOrDestroy() // invalidates the generation, moves to IDLE

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Cancelled)

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `a genuine Task cancellation while still STARTING returns to IDLE and reports a startup failure`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Cancelled)

        assertTrue(effect is MonitoringLifecycleEffect.ReportStartupFailure)
        assertTrue(
            (effect as MonitoringLifecycleEffect.ReportStartupFailure).failure is MonitoringStartupFailure.LocationStartFailed
        )
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `a genuine Task cancellation never leaves the coordinator stuck in STARTING`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onLocationStartResult(generation, LocationStartResult.Cancelled)

        // A later Start must be accepted again — the coordinator must not be wedged in STARTING.
        val effect = coordinator.onActionStart()

        assertTrue(effect is MonitoringLifecycleEffect.StartLocation)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }

    @Test
    fun `a location result for a superseded generation is ignored and never starts audio capture`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val staleGeneration = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onStopOrDestroy() // invalidate; back to IDLE
        coordinator.onActionStart() // a new session begins with a new generation

        val effect = coordinator.onLocationStartResult(staleGeneration, LocationStartResult.Started)

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state) // untouched by the stale callback
    }

    @Test
    fun `a stale Started result never transitions to ACTIVE even though Started normally would`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onStopOrDestroy() // invalidates the generation, state -> IDLE

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from STARTING invalidates the generation and returns StopSession with wasActive false`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.StopSession(wasActive = false), effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from ACTIVE returns StopSession with wasActive true`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.StopSession(wasActive = true), effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from IDLE is a no-op`() {
        val coordinator = MonitoringLifecycleCoordinator()

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }
}
