package hr.sonicpulse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringRefreshCoordinatorTest {

    @Test
    fun `onRefreshRequested while ACTIVE begins a refresh with a generation`() {
        val coordinator = MonitoringRefreshCoordinator()

        val effect = coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE)

        assertTrue(effect is MonitoringRefreshEffect.Begin)
    }

    @Test
    fun `onRefreshRequested while IDLE is ignored`() {
        val coordinator = MonitoringRefreshCoordinator()

        val effect = coordinator.onRefreshRequested(MonitoringLifecycleState.IDLE)

        assertEquals(MonitoringRefreshEffect.None, effect)
    }

    @Test
    fun `onRefreshRequested while STARTING is ignored`() {
        val coordinator = MonitoringRefreshCoordinator()

        val effect = coordinator.onRefreshRequested(MonitoringLifecycleState.STARTING)

        assertEquals(MonitoringRefreshEffect.None, effect)
    }

    @Test
    fun `isCurrent is true for the generation just begun, while still ACTIVE`() {
        val coordinator = MonitoringRefreshCoordinator()
        val generation = (coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE) as MonitoringRefreshEffect.Begin).generation

        assertTrue(coordinator.isCurrent(generation, MonitoringLifecycleState.ACTIVE))
    }

    @Test
    fun `isCurrent is false once lifecycle state is no longer ACTIVE, even for the current generation`() {
        val coordinator = MonitoringRefreshCoordinator()
        val generation = (coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE) as MonitoringRefreshEffect.Begin).generation

        assertFalse(coordinator.isCurrent(generation, MonitoringLifecycleState.IDLE))
    }

    @Test
    fun `a newer refresh invalidates the older generation`() {
        val coordinator = MonitoringRefreshCoordinator()
        val first = (coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE) as MonitoringRefreshEffect.Begin).generation

        val second = (coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE) as MonitoringRefreshEffect.Begin).generation

        assertFalse(coordinator.isCurrent(first, MonitoringLifecycleState.ACTIVE))
        assertTrue(coordinator.isCurrent(second, MonitoringLifecycleState.ACTIVE))
    }

    @Test
    fun `invalidate makes the pending generation no longer current, even while still reported ACTIVE`() {
        val coordinator = MonitoringRefreshCoordinator()
        val generation = (coordinator.onRefreshRequested(MonitoringLifecycleState.ACTIVE) as MonitoringRefreshEffect.Begin).generation

        coordinator.invalidate()

        assertFalse(coordinator.isCurrent(generation, MonitoringLifecycleState.ACTIVE))
    }
}
