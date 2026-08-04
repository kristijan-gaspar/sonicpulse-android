package hr.sonicpulse.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatingCoordinatorTest {

    @Test
    fun `starting an attempt marks locating active`() {
        val coordinator = LocatingCoordinator()

        coordinator.start()

        assertTrue(coordinator.attempt.active)
    }

    @Test
    fun `a matching location result completes the attempt`() {
        val coordinator = LocatingCoordinator()
        val generation = coordinator.start()

        val completed = coordinator.complete(generation)

        assertTrue(completed)
        assertFalse(coordinator.attempt.active)
    }

    @Test
    fun `a matching timeout completes the attempt`() {
        val coordinator = LocatingCoordinator()
        val generation = coordinator.start()

        val completed = coordinator.complete(generation)

        assertTrue(completed)
        assertFalse(coordinator.attempt.active)
    }

    @Test
    fun `a stale timeout cannot complete a newer attempt`() {
        val coordinator = LocatingCoordinator()
        val first = coordinator.start()
        coordinator.start() // supersedes `first`

        val completed = coordinator.complete(first)

        assertFalse(completed)
        assertTrue(coordinator.attempt.active)
    }

    @Test
    fun `a stale location result cannot complete a newer attempt`() {
        val coordinator = LocatingCoordinator()
        val first = coordinator.start()
        coordinator.start() // supersedes `first`

        val completed = coordinator.complete(first)

        assertFalse(completed)
        assertTrue(coordinator.attempt.active)
    }

    @Test
    fun `a second attempt remains active after the first attempt's timeout`() {
        val coordinator = LocatingCoordinator()
        val first = coordinator.start()
        val second = coordinator.start()

        coordinator.complete(first) // the first attempt's (stale) timeout firing late

        assertEquals(second, coordinator.attempt.generation)
        assertTrue(coordinator.attempt.active)
    }

    @Test
    fun `completing an already-completed attempt is a no-op`() {
        val coordinator = LocatingCoordinator()
        val generation = coordinator.start()
        coordinator.complete(generation)

        val completedAgain = coordinator.complete(generation)

        assertFalse(completedAgain)
    }

    @Test
    fun `cancel stops an active attempt without bumping its generation`() {
        val coordinator = LocatingCoordinator()
        val generation = coordinator.start()

        coordinator.cancel()

        assertFalse(coordinator.attempt.active)
        assertEquals(generation, coordinator.attempt.generation)
    }

    @Test
    fun `an attempt cancelled because location services became unavailable ignores its later timeout`() {
        val coordinator = LocatingCoordinator()
        val generation = coordinator.start()
        check(coordinator.attempt.active)

        // Location services become unavailable mid-attempt — the screen cancels it immediately.
        coordinator.cancel()
        assertFalse(coordinator.attempt.active)

        // The attempt's own 15 s timeout still fires later; it must have no effect at all.
        val timeoutCompleted = coordinator.complete(generation)

        assertFalse(timeoutCompleted)
        assertFalse(coordinator.attempt.active)
    }

    @Test
    fun `cancelling when nothing is active is a no-op`() {
        val coordinator = LocatingCoordinator()

        coordinator.cancel()

        assertFalse(coordinator.attempt.active)
        assertEquals(0L, coordinator.attempt.generation)
    }
}
