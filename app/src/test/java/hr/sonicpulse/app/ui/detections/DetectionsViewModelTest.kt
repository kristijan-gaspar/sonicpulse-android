package hr.sonicpulse.app.ui.detections

import hr.sonicpulse.app.domain.model.Detection
import hr.sonicpulse.app.repository.DetectionPage
import hr.sonicpulse.app.repository.DetectionsRepository
import hr.sonicpulse.app.repository.FakeDetectionsRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
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
import org.junit.Assert.assertNull
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
        receivedAtUtc: Instant = Instant.parse("2026-08-03T10:00:00Z"),
        peakTimeClient: Instant? = null
    ) = Detection(
        id = id,
        sequenceNumber = sequenceNumber,
        deviceId = UUID.randomUUID(),
        peakDbfs = peakDbfs,
        latitude = 45.8,
        longitude = 16.0,
        gpsAccuracy = 8.0,
        receivedAtUtc = receivedAtUtc,
        peakTimeClient = peakTimeClient,
        hotspotId = hotspotId
    )

    // --- Initial loading ---

    @Test
    fun `constructing the ViewModel does not automatically issue a request`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository()

        DetectionsViewModel(repository)
        advanceUntilIdle()

        assertTrue(repository.requestedCursors.isEmpty())
    }

    @Test
    fun `an explicit refresh call issues exactly one first-page request`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository()
        val viewModel = DetectionsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(null), repository.requestedCursors)
    }

    @Test
    fun `isInitialLoading is true immediately after refresh, before the first page resolves`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository()
        val viewModel = DetectionsViewModel(repository)

        viewModel.refresh()

        assertTrue(viewModel.uiState.value.isInitialLoading)
    }

    @Test
    fun `a failed initial load produces initialError`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply { throwOnGetPage = IOException("boom") }
        val viewModel = DetectionsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.initialError)
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.refreshError)
    }

    @Test
    fun `a successful initial load replaces the loading state with data`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = DetectionsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isInitialLoading)
        assertEquals(1, state.sections.single().items.size)
    }

    @Test
    fun `a retry after a failed initial load is still treated as an initial load, not a refresh`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply { throwOnGetPage = IOException("boom") }
        val viewModel = DetectionsViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        check(viewModel.uiState.value.initialError)

        repository.throwOnGetPage = null
        repository.pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    // --- Manual refresh (after a successful load) ---

    private fun viewModelWithInitialRefresh(repository: FakeDetectionsRepository): DetectionsViewModel {
        val viewModel = DetectionsViewModel(repository)
        viewModel.refresh()
        return viewModel
    }

    @Test
    fun `a manual refresh preserves loaded items, cursor and canLoadMore while it is running`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 5L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()
        check(viewModel.uiState.value.sections.isNotEmpty())
        check(viewModel.uiState.value.canLoadMore)

        viewModel.refresh()

        // Asserted synchronously, before the second refresh's coroutine has a chance to run.
        val state = viewModel.uiState.value
        assertTrue(state.sections.isNotEmpty())
        assertTrue(state.canLoadMore)
        assertTrue(state.isRefreshing)
        assertFalse(state.isInitialLoading)
    }

    @Test
    fun `a manual refresh preserves the selected filter while it is running`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = UUID.randomUUID())), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()
        viewModel.selectFilter(DetectionsFilter.Grouped)
        check(viewModel.uiState.value.selectedFilter == DetectionsFilter.Grouped)

        viewModel.refresh()

        assertEquals(DetectionsFilter.Grouped, viewModel.uiState.value.selectedFilter)
    }

    @Test
    fun `a failed manual refresh preserves loaded items, cursor, canLoadMore and filter`() = runTest(testDispatcher) {
        val original = detection(peakDbfs = -1.0)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(original), nextCursor = 5L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()
        viewModel.selectFilter(DetectionsFilter.Ungrouped)
        check(viewModel.uiState.value.canLoadMore)

        repository.throwOnGetPage = IOException("boom")
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(-1.0), state.sections.flatMap { it.items }.map { it.peakDbfs })
        assertTrue(state.canLoadMore)
        assertEquals(DetectionsFilter.Ungrouped, state.selectedFilter)
    }

    @Test
    fun `a failed manual refresh does not set initialError`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        repository.throwOnGetPage = IOException("boom")
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.initialError)
    }

    @Test
    fun `a failed manual refresh exposes refreshError`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        repository.throwOnGetPage = IOException("boom")
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.refreshError)
    }

    @Test
    fun `a successful retry after a failed manual refresh replaces the old list and applies the new cursor`() =
        runTest(testDispatcher) {
            val old = detection(peakDbfs = -1.0)
            val repository = FakeDetectionsRepository().apply {
                pages = mapOf(null to DetectionPage(items = listOf(old), nextCursor = 5L))
            }
            val viewModel = viewModelWithInitialRefresh(repository)
            advanceUntilIdle()

            repository.throwOnGetPage = IOException("boom")
            viewModel.refresh()
            advanceUntilIdle()
            check(viewModel.uiState.value.refreshError)

            val replacement = detection(peakDbfs = -2.0)
            repository.throwOnGetPage = null
            repository.pages = mapOf(null to DetectionPage(items = listOf(replacement), nextCursor = 9L))
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(-2.0), state.sections.flatMap { it.items }.map { it.peakDbfs })
            assertFalse(state.refreshError)

            // The new cursor (9L) is what a following loadNextPage() would use.
            viewModel.loadNextPage()
            advanceUntilIdle()
            assertEquals(listOf(null, null, null, 9L), repository.requestedCursors)
        }

    @Test
    fun `an older in-flight refresh result cannot overwrite a newer refresh result`() = runTest(testDispatcher) {
        val repository = ControllableDetectionsRepository()
        val viewModel = DetectionsViewModel(repository)
        viewModel.refresh()
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

    // --- Refresh clears obsolete paging state (item 13) ---

    @Test
    fun `starting a refresh while loadNextPage is active immediately clears paging loading and error state`() =
        runTest(testDispatcher) {
            val repository = ControllableDetectionsRepository()
            val viewModel = DetectionsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            repository.deferredQueue[0].complete(DetectionPage(items = listOf(detection(peakDbfs = -1.0)), nextCursor = 1L))
            advanceUntilIdle()

            viewModel.loadNextPage()
            advanceUntilIdle()
            check(viewModel.uiState.value.isLoadingNextPage)

            viewModel.refresh()

            // Asserted synchronously, before the new refresh's own coroutine resolves.
            assertFalse(viewModel.uiState.value.isLoadingNextPage)
            assertFalse(viewModel.uiState.value.pagingError)
        }

    @Test
    fun `a stale loadNextPage result arriving after a refresh started has no effect`() = runTest(testDispatcher) {
        val repository = ControllableDetectionsRepository()
        val viewModel = DetectionsViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        repository.deferredQueue[0].complete(DetectionPage(items = listOf(detection(peakDbfs = -1.0)), nextCursor = 1L))
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()
        val stalePagingDeferred = repository.deferredQueue[1]

        viewModel.refresh()
        advanceUntilIdle()
        val newRefreshDeferred = repository.deferredQueue[2]

        // The stale next-page result resolves first...
        stalePagingDeferred.complete(DetectionPage(items = listOf(detection(peakDbfs = -2.0)), nextCursor = null))
        advanceUntilIdle()
        // ...then the new refresh's own result.
        newRefreshDeferred.complete(DetectionPage(items = listOf(detection(peakDbfs = -3.0)), nextCursor = null))
        advanceUntilIdle()

        val finalPeaks = viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs }
        assertEquals(listOf(-3.0), finalPeaks)
    }

    // --- Paging correctness ---

    @Test
    fun `one loadNextPage call produces exactly one request`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 1L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null, 1L), repository.requestedCursors)
    }

    @Test
    fun `concurrent duplicate loadNextPage calls are ignored`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = 1L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()
        check(!viewModel.uiState.value.canLoadMore)

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null), repository.requestedCursors)
    }

    @Test
    fun `paging error remains exposed when the current filter has zero matches`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = 1L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()
        viewModel.selectFilter(DetectionsFilter.Grouped)
        check(viewModel.uiState.value.emptyState == DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable)

        repository.throwOnGetPage = IOException("boom")
        viewModel.loadNextPage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.pagingError)
        assertEquals(DetectionsEmptyState.NoCurrentMatchesMorePagesAvailable, state.emptyState)
    }

    // --- Deduplication ---

    @Test
    fun `duplicate ids inside the first page are removed, keeping the first occurrence`() = runTest(testDispatcher) {
        val id = UUID.randomUUID()
        val first = detection(id = id, peakDbfs = -1.0)
        val duplicate = detection(id = id, peakDbfs = -2.0)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(first, duplicate), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        val items = viewModel.uiState.value.sections.flatMap { it.items }
        assertEquals(1, items.size)
        assertEquals(-1.0, items.single().peakDbfs, 0.0)
    }

    @Test
    fun `duplicate ids inside a later page are removed, keeping the first occurrence`() = runTest(testDispatcher) {
        val dupId = UUID.randomUUID()
        val firstInPage2 = detection(id = dupId, peakDbfs = -2.0)
        val duplicateInPage2 = detection(id = dupId, peakDbfs = -3.0)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(items = listOf(detection(peakDbfs = -1.0)), nextCursor = 1L),
                1L to DetectionPage(items = listOf(firstInPage2, duplicateInPage2), nextCursor = null)
            )
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val items = viewModel.uiState.value.sections.flatMap { it.items }
        assertEquals(2, items.size)
        assertEquals(1, items.count { it.id == dupId })
        assertEquals(-2.0, items.single { it.id == dupId }.peakDbfs, 0.0)
    }

    @Test
    fun `ids shared between an already-loaded page and a later page are removed`() = runTest(testDispatcher) {
        val shared = detection(peakDbfs = -1.0)
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(items = listOf(shared), nextCursor = 1L),
                1L to DetectionPage(items = listOf(shared, detection(peakDbfs = -2.0)), nextCursor = null)
            )
        }
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val order = viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs }
        assertEquals(listOf(-1.0, -2.0, -3.0), order)
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
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Ungrouped)

        assertEquals(listOf(-2.0), viewModel.uiState.value.sections.flatMap { it.items }.map { it.peakDbfs })
    }

    @Test
    fun `pagination remains available while a restrictive filter is selected`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = 1L))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DetectionsFilter.Grouped)

        assertTrue(viewModel.uiState.value.canLoadMore)
    }

    @Test
    fun `selecting a filter never triggers a new backend request`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection()), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        assertEquals(DetectionsEmptyState.NoDetectionsAtAll, viewModel.uiState.value.emptyState)
    }

    @Test
    fun `NoMatchesForFilter when loaded pages have no matches and no more pages exist`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
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
            val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sections.single().items.single().grouped)
    }

    @Test
    fun `a null hotspotId maps to grouped false`() = runTest(testDispatcher) {
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(null to DetectionPage(items = listOf(detection(hotspotId = null)), nextCursor = null))
        }
        val viewModel = viewModelWithInitialRefresh(repository)
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
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        val sectionDates = viewModel.uiState.value.sections.map { it.date }
        assertEquals(
            listOf(day1.atZone(zone).toLocalDate(), day2.atZone(zone).toLocalDate()),
            sectionDates
        )
    }

    // --- Timestamp presentation ---

    @Test
    fun `list and detail timestamps are derived from receivedAtUtc, never peakTimeClient`() = runTest(testDispatcher) {
        val receivedAt = Instant.parse("2026-08-03T10:00:00Z")
        val unrelatedPeakTimeClient = Instant.parse("2020-01-01T00:00:00Z")
        val repository = FakeDetectionsRepository().apply {
            pages = mapOf(
                null to DetectionPage(
                    items = listOf(detection(receivedAtUtc = receivedAt, peakTimeClient = unrelatedPeakTimeClient)),
                    nextCursor = null
                )
            )
        }
        val viewModel = viewModelWithInitialRefresh(repository)
        advanceUntilIdle()

        val item = viewModel.uiState.value.sections.single().items.single()
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        assertEquals(listTimestampTextFor(receivedAt, zone, locale), item.listTimestampText)
        assertEquals(detailTimestampTextFor(receivedAt, zone, locale), item.detailTimestampText)
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
