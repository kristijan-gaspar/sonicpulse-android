package hr.sonicpulse.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRenderCoordinatorTest {

    @Test
    fun `a new map instance starts in Loading`() {
        val coordinator = MapRenderCoordinator()

        val generation = coordinator.newInstance()

        assertEquals(MapRenderState.Loading, coordinator.state)
        assertEquals(1, generation)
    }

    @Test
    fun `the current instance succeeding becomes Loaded`() {
        val coordinator = MapRenderCoordinator()
        val generation = coordinator.newInstance()

        coordinator.onLoadFinished(generation)

        assertEquals(MapRenderState.Loaded, coordinator.state)
    }

    @Test
    fun `the current instance failing becomes Failed with its reason`() {
        val coordinator = MapRenderCoordinator()
        val generation = coordinator.newInstance()

        coordinator.onLoadFailed(generation, "style parse error")

        assertEquals(MapRenderState.Failed("style parse error"), coordinator.state)
    }

    @Test
    fun `a stale success from an older instance is ignored`() {
        val coordinator = MapRenderCoordinator()
        val older = coordinator.newInstance()
        coordinator.newInstance() // supersedes `older`

        coordinator.onLoadFinished(older)

        assertEquals(MapRenderState.Loading, coordinator.state)
    }

    @Test
    fun `a stale failure from an older instance is ignored`() {
        val coordinator = MapRenderCoordinator()
        val older = coordinator.newInstance()
        val newer = coordinator.newInstance()
        coordinator.onLoadFinished(newer)

        coordinator.onLoadFailed(older, "stale reason")

        assertEquals(MapRenderState.Loaded, coordinator.state)
    }

    @Test
    fun `retry creates a new generation`() {
        val coordinator = MapRenderCoordinator()
        val first = coordinator.newInstance()

        val second = coordinator.newInstance()

        assertEquals(first + 1, second)
    }

    @Test
    fun `a fresh instance after a failure clears the previous map error`() {
        val coordinator = MapRenderCoordinator()
        val failed = coordinator.newInstance()
        coordinator.onLoadFailed(failed, "boom")
        check(coordinator.state is MapRenderState.Failed)

        coordinator.newInstance()

        assertEquals(MapRenderState.Loading, coordinator.state)
    }
}
