package hr.sonicpulse.app.ui.detections

import hr.sonicpulse.app.domain.model.Detection
import hr.sonicpulse.app.repository.DetectionPage
import hr.sonicpulse.app.repository.DetectionsRepository
import hr.sonicpulse.app.repository.FakeDetectionsRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun detection(
        id: UUID = UUID.randomUUID(),
        sequenceNumber: Long = 1L,
        peakDbfs: Double = -10.0,
        hotspotId: UUID? = null,
        receivedAtUtc: Instant = Instant.parse("2026-08-03T10:00:00Z")
    ) = Detection(
        id = id,
        sequenceNumber = sequenceNumber,
        deviceId = UUID.randomUUID(),
        peakDbfs = peakDbfs,
        latitude = 45.8,
        longitude = 16.0,
        gpsAccuracy = 8.0,
        receivedAtUtc = receivedAtUtc,
        peakTimeClient = null,
        hotspotId = hotspotId
    )

    // --- Single initial-load trigger ---

    @Test
    fun `init triggers exactly one initial load`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository()

        DetectionsViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf(null), repository.requestedCursors)
    }

    @Test
    fun `isInitialLoading is true before the first page resolves`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository()

        val viewModel = DetectionsViewModel(repository)

        assertTrue(viewModel.uiState.value.isInitialLoading)
    }

    @Test
    fun `a successful initial load populates sections and clears isInitialLoading`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }

        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isInitialLoading)
        assertEquals(1, state.sections.single().items.size)
    }

    // --- refresh() ---

    @Test
    fun `refresh clears previous items, cursor and errors before applying page 1`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 5L))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()
        check(viewModel.uiState.value.sections.isNotEmpty())

        viewModel.refresh()

        // Asserted synchronously, before the new coroutine has a chance to run.
        assertTrue(viewModel.uiState.value.sections.isEmpty())
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertFalse(viewModel.uiState.value.canLoadMore)
    }

    @Test
    fun `a failed initial load sets initialError and does not mark the initial load complete`() =
        runTest(testDispatcher) {
            val repository = FakeDetectionsRepository().apply { throwOnGetPage = IOException("boom") }

            val viewModel = DetectionsViewModel(repository)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.initialError)
            assertFalse(viewModel.uiState.value.isInitialLoading)

            // A retry after a failed initial load is still treated as an initial load, not a refresh.
            repository.throwOnGetPage = null
            repository.pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
            viewModel.refresh()
            assertTrue(viewModel.uiState.value.isInitialLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    // --- Paging correctness ---

    @Test
    fun `duplicate detection ids from a later page are not added twice`() = runTest(testDispatcher) {
        val shared = detection(peakDbfs = -1.0)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(items = listOf(shared), nextCursor = 1L),
                1L to DetectionPage(items = listOf(shared, detection(peakDbfs = -2.0)), nextCursor = null)
            )
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val allItems = viewModel.uiState.value.sections.flatMap { it.items }
        assertEquals(2, allItems.size)
        assertEquals(2, allItems.map { it.id }.distinct().size)
        assertEquals(1, allItems.count { it.id == shared.id })
    }

    @Test
    fun `items stay ordered newest-to-oldest across pages`() = runTest(testDispatcher) {
        val newest = detection(peakDbfs = -1.0, receivedAtUtc = Instant.parse("2026-08-03T12:00:00Z"))
        val middle = detection(peakDbfs = -2.0, receivedAtUtc = Instant.parse("2026-08-03T11:00:00Z"))
        val oldest = detection(peakDbfs = -3.0, receivedAtUtc = Instant.parse("2026-08-03T10:00:00Z"))
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(items = listOf(newest, middle), nextCursor = 1L),
                1L to DetectionPage(items = listOf(oldest), nextCursor = null)
            )
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val order = viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs }
        assertEquals(listOf(-1.0, -2.0, -3.0), order)
    }

    @Test
    fun `concurrent duplicate loadNextPage calls are ignored`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 1L))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null, 1L), repository.requestedCursors)
    }

    @Test
    fun `a failed next-page request preserves already loaded items`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 1L))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()
        val itemsBefore = viewModel.uiState.value.sections.flatMap { it.items }

        repository.throwOnGetPage = IOException("boom")
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(itemsBefore, viewModel.uiState.value.sections.flatMap { it.items })
        assertTrue(viewModel.uiState.value.pagingError)
    }

    @Test
    fun `retry after a failed next-page request uses the same cursor`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 1L))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        repository.throwOnGetPage = IOException("boom")
        viewModel.loadNextPage()
        advanceUntilIdle()

        repository.throwOnGetPage = null
        repository.pages = repository.pages + (1L to DetectionPage(items = listOf(detection()), nextCursor = null))
        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null, 1L, 1L), repository.requestedCursors)
    }

    @Test
    fun `nextCursor null disables further paging until refresh`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()
        check(!viewModel.uiState.value.canLoadMore)

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null), repository.requestedCursors)
    }

    @Test
    fun `an older in-flight refresh result cannot overwrite a newer refresh result`() = runTest(testDispatcher) {
        val repository = ControllableDetectionsRepository()
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()
        val older = repository.deferredQueue.single()

        viewModel.refresh()
        advanceUntilIdle()
        val newer = repository.deferredQueue.last()

        // Out-of-order resolution: the newer request's result arrives first...
        newer.complete(DetectionPage(items = listOf(detection(peakDbfs = -99.0)), nextCursor = null))
        advanceUntilIdle()
        // ...then the stale older request finally resolves too.
        older.complete(DetectionPage(items = listOf(detection(peakDbfs = -1.0)), nextCursor = null))
        advanceUntilIdle()

        val finalItems = viewModel.uiState.value.sections.flatMap { it.items }
        assertEquals(listOf(-99.0), finalItems.map { it.peakDbfs })
    }

    // --- Filters ---

    @Test
    fun `Today filter only shows detections from the current local date`() = runTest(testDispatcher) {
        val today = Instant.now()
        val longAgo = Instant.parse("2020-01-01T00:00:00Z")
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(
                    items = listOf(detection(peakDbfs = -1.0, receivedAtUtc = today), detection(peakDbfs = -2.0, receivedAtUtc = longAgo)),
                    nextCursor = null
                )
            )
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Today)

        val visible = viewModel.uiState.value.sections.flatMap { it.items }
        assertEquals(listOf(-1.0), visible.map { it.peakDbfs })
    }

    @Test
    fun `Grouped filter only shows detections with a hotspotId`() = runTest(testDispatcher) {
        val grouped = detection(peakDbfs = -1.0, hotspotId = UUID.randomUUID())
        val ungrouped = detection(peakDbfs = -2.0, hotspotId = null)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(grouped, ungrouped), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Grouped)

        assertEquals(listOf(-1.0), viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs })
    }

    @Test
    fun `Ungrouped filter only shows detections without a hotspotId`() = runTest(testDispatcher) {
        val grouped = detection(peakDbfs = -1.0, hotspotId = UUID.randomUUID())
        val ungrouped = detection(peakDbfs = -2.0, hotspotId = null)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(grouped, ungrouped), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Ungrouped)

        assertEquals(listOf(-2.0), viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs })
    }

    @Test
    fun `pagination remains available while a restrictive filter is selected`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = 1L))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Grouped)

        assertTrue(viewModel.uiState.value.canLoadMore)
    }

    @Test
    fun `selecting a filter never triggers a new backend request`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Grouped)
        advanceUntilIdle()

        assertEquals(listOf(null), repository.requestedCursors)
    }

    // --- Empty states ---

    @Test
    fun `NoDetectionsAtAll when the device truly has no detections`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = emptyList(), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        assertEquals(DetectionsEmptyState.NoDetectionsAtAll, viewModel.uiState.value.emptyState)
    }

    @Test
    fun `NoMatchesForFilter when loaded pages have no matches and no more pages exist`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Grouped)

        assertEquals(DetectionsEmptyState.NoMatchesForFilter, viewModel.uiState.value.emptyState)
    }

    @Test
    fun `NoCurrentMatchesMorePagesAvailable when no current matches exist but more pages remain`() =
        runTest(testDispatcher) {
            val repository = FakeDetectionsRepository().apply {
                pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = 1L))
            }
            val viewModel = DetectionsViewModel(repository)
            advanceUntilIdle()

            viewModel.selectFilter(DetectionsFilter.Grouped)

            val state = viewModel.uiState.value
            assertEquals(DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable, state.emptyState)
            assertTrue(state.canLoadMore)
        }

    // --- Status semantics ---

    @Test
    fun `a non-null hotspotId maps to grouped true`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = UUID.randomUUID())), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sections.single().items.single().grouped)
    }

    @Test
    fun `a null hotspotId maps to grouped false`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.sections.single().items.single().grouped)
    }

    // --- Date grouping ---

    @Test
    fun `items are grouped into sections by local calendar date, newest date first`() = runTest(testDispatcher) {
        val zone = ZoneId.systemDefault()
        val day1 = Instant.parse("2026-08-03T10:00:00Z")
        val day2 = Instant.parse("2026-08-02T10:00:00Z")
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(
                    items = listOf(detection(receivedAtUtc = day1), detection(receivedAtUtc = day2)),
                    nextCursor = null
                )
            )
        }
        val viewModel = DetectionsViewModel(repository)
        advanceUntilIdle()

        val sectionDates = viewModel.uiState.value.sections.map { it.date }
        assertEquals(
            listOf(day1.atZone(zone).toLocalDate(), day2.atZone(zone).toLocalDate()),
            sectionDates
        )
    }
}

/** Lets a test control exactly when each `getDetectionsPage` call resolves, to prove genuine
 * out-of-order (race) behavior that [FakeDetectionsRepository]'s synchronous fake cannot simulate. */
private class ControllableDetectionsRepository : DetectionsRepository {
    val deferredQueue = mutableListOf<CompletableDeferred<DetectionPage>>()

    override suspend fun getDetectionsPage(cursor: Long?, limit: Int): DetectionPage {
        val deferred = CompletableDeferred<DetectionPage>()
        deferredQueue += deferred
        return deferred.await()
    }
}
