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
        coordinator.onStopOrDestroy() // invalidates the generation, moves to STOPPING
        coordinator.onTeardownComplete() // teardown finishes, no restart pending -> IDLE

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
        coordinator.onStopOrDestroy() // invalidate; moves to STOPPING
        coordinator.onTeardownComplete() // teardown finishes -> IDLE
        coordinator.onActionStart() // a new session begins with a new generation

        val effect = coordinator.onLocationStartResult(staleGeneration, LocationStartResult.Started)

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state) // untouched by the stale callback
    }

    @Test
    fun `a stale Started result never transitions to ACTIVE even though Started normally would`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onStopOrDestroy() // invalidates the generation, state -> STOPPING

        val effect = coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.STOPPING, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from STARTING invalidates the generation and moves to STOPPING with wasActive false`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.StopSession(wasActive = false), effect)
        assertEquals(MonitoringLifecycleState.STOPPING, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from ACTIVE moves to STOPPING with wasActive true`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onLocationStartResult(generation, LocationStartResult.Started)

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.StopSession(wasActive = true), effect)
        assertEquals(MonitoringLifecycleState.STOPPING, coordinator.state)
    }

    @Test
    fun `onStopOrDestroy from IDLE is a no-op`() {
        val coordinator = MonitoringLifecycleCoordinator()

        val effect = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    // --- STOPPING / pending restart / onTeardownComplete ---

    @Test
    fun `a Start while STOPPING does not begin a new session immediately`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val generation = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onLocationStartResult(generation, LocationStartResult.Started)
        coordinator.onStopOrDestroy()

        val effect = coordinator.onActionStart()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.STOPPING, coordinator.state)
    }

    @Test
    fun `repeated Start commands while STOPPING queue only one restart, not several`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()
        coordinator.onStopOrDestroy()

        coordinator.onActionStart()
        coordinator.onActionStart()
        coordinator.onActionStart()
        val effect = coordinator.onTeardownComplete()

        // A single StartLocation, not e.g. a state that would suggest three queued restarts —
        // the only observable proxy for "how many restarts" is that exactly one StartLocation
        // effect is produced here, and a *second* onTeardownComplete() call (below) finds nothing
        // left queued.
        assertTrue(effect is MonitoringLifecycleEffect.StartLocation)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }

    @Test
    fun `repeated Stop commands while STOPPING do not create a second teardown signal`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()
        val first = coordinator.onStopOrDestroy()
        check(first is MonitoringLifecycleEffect.StopSession)

        val second = coordinator.onStopOrDestroy()
        val third = coordinator.onStopOrDestroy()

        assertEquals(MonitoringLifecycleEffect.None, second)
        assertEquals(MonitoringLifecycleEffect.None, third)
        assertEquals(MonitoringLifecycleState.STOPPING, coordinator.state)
    }

    @Test
    fun `onTeardownComplete with no pending restart returns to IDLE`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()
        coordinator.onStopOrDestroy()

        val effect = coordinator.onTeardownComplete()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onTeardownComplete with a pending restart starts a new session with a fresh generation`() {
        val coordinator = MonitoringLifecycleCoordinator()
        val oldGeneration = (coordinator.onActionStart() as MonitoringLifecycleEffect.StartLocation).generation
        coordinator.onStopOrDestroy()
        coordinator.onActionStart() // queues the restart

        val effect = coordinator.onTeardownComplete()

        assertTrue(effect is MonitoringLifecycleEffect.StartLocation)
        val newGeneration = (effect as MonitoringLifecycleEffect.StartLocation).generation
        assertTrue("expected a fresh generation, was the same as the old one", newGeneration != oldGeneration)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }

    @Test
    fun `a Stop received after a restart was queued clears the queued restart`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()
        coordinator.onStopOrDestroy()
        coordinator.onActionStart() // queues a restart
        coordinator.onStopOrDestroy() // Stop again before teardown finished: must clear the queued restart

        val effect = coordinator.onTeardownComplete()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onTeardownComplete called from a state other than STOPPING is a defensive no-op`() {
        val coordinator = MonitoringLifecycleCoordinator()

        val effect = coordinator.onTeardownComplete()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    // --- onSynchronousStartupAbort ---

    @Test
    fun `onSynchronousStartupAbort from STARTING returns straight to IDLE, never through STOPPING`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()

        val effect = coordinator.onSynchronousStartupAbort()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `onSynchronousStartupAbort from any other state is a no-op`() {
        val coordinator = MonitoringLifecycleCoordinator()

        val effect = coordinator.onSynchronousStartupAbort()

        assertEquals(MonitoringLifecycleEffect.None, effect)
        assertEquals(MonitoringLifecycleState.IDLE, coordinator.state)
    }

    @Test
    fun `a Start after onSynchronousStartupAbort is accepted immediately, not queued`() {
        val coordinator = MonitoringLifecycleCoordinator()
        coordinator.onActionStart()
        coordinator.onSynchronousStartupAbort()

        val effect = coordinator.onActionStart()

        assertTrue(effect is MonitoringLifecycleEffect.StartLocation)
        assertEquals(MonitoringLifecycleState.STARTING, coordinator.state)
    }
}
