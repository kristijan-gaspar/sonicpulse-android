package hr.sonicpulse.app.ui.map

import hr.sonicpulse.app.data.datastore.FakePermissionRequestHistory
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.repository.FakeHotspotsRepository
import hr.sonicpulse.app.repository.HotspotsRepository
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun hotspot(deviceCount: Int = 3) = Hotspot(
        id = UUID.randomUUID(),
        latitude = 45.8,
        longitude = 16.0,
        radiusMeters = 100.0,
        confidence = 84,
        deviceCount = deviceCount,
        firstReceivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"),
        lastReceivedAtUtc = Instant.parse("2026-08-03T10:00:12Z")
    )

    // --- Screen entry ---

    @Test
    fun `constructing the ViewModel does not automatically issue a request`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository()

        MapViewModel(repository, FakePermissionRequestHistory())
        advanceUntilIdle()

        assertTrue(repository.requestedSinceHours.isEmpty())
    }

    @Test
    fun `screen entry issues exactly one 24-hour request`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())

        viewModel.onScreenEntered()
        advanceUntilIdle()

        assertEquals(listOf(24), repository.requestedSinceHours)
    }

    @Test
    fun `repeated reads of uiState after screen entry issue no additional requests`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()

        repeat(5) { viewModel.uiState.value }
        advanceUntilIdle()

        assertEquals(listOf(24), repository.requestedSinceHours)
    }

    @Test
    fun `initial loading and initial error are distinct`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply { throwOnGetHotspots = IOException("boom") }
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())

        viewModel.onScreenEntered()
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.initialError)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertTrue(viewModel.uiState.value.initialError)
    }

    @Test
    fun `successful initial load commits 24 hours`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply {
            hotspots = mapOf(24 to listOf(hotspot()))
        }
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())

        viewModel.onScreenEntered()
        advanceUntilIdle()

        assertEquals(HotspotTimeRange.Last24Hours, viewModel.uiState.value.committedRange)
        assertEquals(1, viewModel.uiState.value.hotspots.size)
    }

    @Test
    fun `empty successful response produces the empty state`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to emptyList()) }
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())

        viewModel.onScreenEntered()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isInitialLoading)
        assertFalse(state.initialError)
        assertTrue(state.hotspots.isEmpty())
    }

    // --- Range selection ---

    private fun viewModelAfterInitialLoad(repository: FakeHotspotsRepository): MapViewModel {
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        return viewModel
    }

    @Test
    fun `selecting 72 hours issues exactly one request`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(hotspot())) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()

        assertEquals(listOf(24, 72), repository.requestedSinceHours)
    }

    @Test
    fun `selecting 168 hours issues exactly one request`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(hotspot())) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last7Days)
        advanceUntilIdle()

        assertEquals(listOf(24, 168), repository.requestedSinceHours)
    }

    @Test
    fun `selecting the already-committed range is a no-op`() = runTest(testDispatcher) {
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(hotspot())) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last24Hours)
        advanceUntilIdle()

        assertEquals(listOf(24), repository.requestedSinceHours)
    }

    @Test
    fun `selecting the already-pending range is a no-op`() = runTest(testDispatcher) {
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(listOf(hotspot()))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()
        check(viewModel.uiState.value.pendingRange == HotspotTimeRange.Last3Days)

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()

        assertEquals(listOf(24, 72), repository.requestedSinceHours)
    }

    @Test
    fun `subsequent range loading preserves committed hotspots`() = runTest(testDispatcher) {
        val original = hotspot()
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(listOf(original))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)

        val state = viewModel.uiState.value
        assertEquals(listOf(original), state.hotspots)
        assertEquals(HotspotTimeRange.Last24Hours, state.committedRange)
        assertEquals(HotspotTimeRange.Last3Days, state.pendingRange)
    }

    @Test
    fun `successful range load replaces hotspots and commits the range`() = runTest(testDispatcher) {
        val original = hotspot()
        val replacement = hotspot()
        val repository = FakeHotspotsRepository().apply {
            hotspots = mapOf(24 to listOf(original), 72 to listOf(replacement))
        }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(replacement), state.hotspots)
        assertEquals(HotspotTimeRange.Last3Days, state.committedRange)
        assertNull(state.pendingRange)
    }

    @Test
    fun `failed range load retains hotspots and reverts to the committed range`() = runTest(testDispatcher) {
        val original = hotspot()
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(original)) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        repository.throwOnGetHotspots = IOException("boom")
        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(original), state.hotspots)
        assertEquals(HotspotTimeRange.Last24Hours, state.committedRange)
        assertNull(state.pendingRange)
        assertTrue(state.subsequentError)
    }

    // --- Manual refresh ---

    @Test
    fun `manual refresh preserves data while running`() = runTest(testDispatcher) {
        val original = hotspot()
        val controllable = ControllableHotspotsRepository()
        val viewModel = MapViewModel(controllable, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        controllable.deferredQueue[0].complete(listOf(original))
        advanceUntilIdle()

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(listOf(original), state.hotspots)
        assertTrue(state.isLoadingSubsequent)
        assertNull(state.pendingRange)
    }

    @Test
    fun `failed manual refresh preserves data`() = runTest(testDispatcher) {
        val original = hotspot()
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(original)) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        repository.throwOnGetHotspots = IOException("boom")
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(original), state.hotspots)
        assertTrue(state.subsequentError)
    }

    @Test
    fun `successful manual refresh replaces data`() = runTest(testDispatcher) {
        val original = hotspot()
        val replacement = hotspot()
        val repository = FakeHotspotsRepository().apply { hotspots = mapOf(24 to listOf(original)) }
        val viewModel = viewModelAfterInitialLoad(repository)
        advanceUntilIdle()

        repository.hotspots = mapOf(24 to listOf(replacement))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(replacement), viewModel.uiState.value.hotspots)
        assertFalse(viewModel.uiState.value.subsequentError)
    }

    // --- Race safety ---

    @Test
    fun `a newer range request cancels the older repository coroutine`() = runTest(testDispatcher) {
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(listOf(hotspot()))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()
        viewModel.selectRange(HotspotTimeRange.Last7Days)
        advanceUntilIdle()

        assertEquals(listOf(1), repository.cancelledIndices)
    }

    @Test
    fun `stale results cannot overwrite the newer range`() = runTest(testDispatcher) {
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        val initial = repository.deferredQueue[0]
        initial.complete(listOf(hotspot()))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()
        val older = repository.deferredQueue[1]

        viewModel.selectRange(HotspotTimeRange.Last7Days)
        advanceUntilIdle()
        val newer = repository.deferredQueue[2]

        val newerHotspot = hotspot()
        newer.complete(listOf(newerHotspot))
        advanceUntilIdle()
        older.complete(listOf(hotspot()))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(newerHotspot), state.hotspots)
        assertEquals(HotspotTimeRange.Last7Days, state.committedRange)
    }

    @Test
    fun `cancellation exposes no error`() = runTest(testDispatcher) {
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(listOf(hotspot()))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days)
        advanceUntilIdle()
        viewModel.selectRange(HotspotTimeRange.Last7Days)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.initialError)
        assertFalse(state.subsequentError)
    }

    @Test
    fun `identity-safe cleanup cannot clear a newer job`() = runTest(testDispatcher) {
        val repository = ControllableHotspotsRepository()
        val viewModel = MapViewModel(repository, FakePermissionRequestHistory())
        viewModel.onScreenEntered()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(listOf(hotspot()))
        advanceUntilIdle()

        viewModel.selectRange(HotspotTimeRange.Last3Days) // superseded, deferredQueue[1]
        advanceUntilIdle()
        viewModel.selectRange(HotspotTimeRange.Last7Days) // active, deferredQueue[2]
        advanceUntilIdle()
        check(repository.deferredQueue.size == 3)

        // If the superseded (older, index-1) job's completion had wrongly cleared loadJob after
        // index-2 became active, this refresh's loadJob?.cancel() would silently no-op — index-2
        // would keep running uncancelled instead of being torn down.
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(repository.cancelledIndices.contains(2))
        assertEquals(4, repository.deferredQueue.size)
    }
}

/** Lets a test control exactly when each `getHotspots` call resolves, and records which calls
 * were actually cancelled — mirrors `ControllableDetectionsRepository` in
 * `DetectionsViewModelTest`. */
private class ControllableHotspotsRepository : HotspotsRepository {
    val deferredQueue = mutableListOf<CompletableDeferred<List<Hotspot>>>()
    val cancelledIndices = mutableListOf<Int>()
    val requestedSinceHours = mutableListOf<Int>()

    override suspend fun getHotspots(sinceHours: Int): List<Hotspot> {
        requestedSinceHours += sinceHours
        val deferred = CompletableDeferred<List<Hotspot>>()
        val index = deferredQueue.size
        deferredQueue += deferred
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            cancelledIndices += index
            throw e
        }
    }
}
