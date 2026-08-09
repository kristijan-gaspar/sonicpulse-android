package hr.sonicpulse.app.ui.detections

import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves selectedDetectionFrom() re-derives the selected detection from the currently loaded
 * sections by id instead of trusting a captured snapshot — the same "detail can go stale after the
 * underlying list refreshes" bug class SelectedHotspotFromTest already covers for the Map screen.
 */
class SelectedDetectionFromTest {

    private fun detection(
        id: UUID = UUID.randomUUID(),
        peakDbfs: Double = -12.3,
        grouped: Boolean = false
    ) = DetectionHistoryItemUiModel(
        id = id,
        peakDbfs = peakDbfs,
        listTimestampText = "14:32:07",
        detailTimestampText = "8 Aug 2026, 14:32:07",
        receivedAtServerText = "8 Aug 2026, 14:32:08",
        latitudeText = "45.80000",
        longitudeText = "16.00000",
        gpsAccuracyText = "8",
        grouped = grouped
    )

    private fun sectionsOf(vararg items: DetectionHistoryItemUiModel) =
        listOf(DetectionDateSection(date = LocalDate.of(2026, 8, 8), items = items.toList()))

    @Test
    fun `no selection returns null regardless of the loaded sections`() {
        assertNull(selectedDetectionFrom(sectionsOf(detection()), selectedId = null))
    }

    @Test
    fun `an unchanged detection remains selected`() {
        val target = detection()

        val result = selectedDetectionFrom(sectionsOf(target), selectedId = target.id)

        assertEquals(target, result)
    }

    @Test
    fun `a selected detection updated by a refresh reflects the new data, not the stale snapshot`() {
        val id = UUID.randomUUID()
        val updated = detection(id = id, peakDbfs = -3.0, grouped = true)
        val staleSections = sectionsOf(detection(id = id, peakDbfs = -40.0, grouped = false))
        check(selectedDetectionFrom(staleSections, id) != updated) { "test setup invalid" }

        val result = selectedDetectionFrom(sectionsOf(updated), selectedId = id)

        assertEquals(updated, result)
        assertEquals(true, result?.grouped)
    }

    @Test
    fun `a selected detection no longer present (evicted by a refresh or page reset) returns null, closing the sheet`() {
        val evictedId = UUID.randomUUID()
        val stillPresent = detection()

        val result = selectedDetectionFrom(sectionsOf(stillPresent), selectedId = evictedId)

        assertNull(result)
    }

    @Test
    fun `a selected detection filtered out by a filter change returns null, closing the sheet`() {
        val filteredOutId = UUID.randomUUID()
        val remainingAfterFilter = sectionsOf(detection(), detection())

        val result = selectedDetectionFrom(remainingAfterFilter, selectedId = filteredOutId)

        assertNull(result)
    }

    @Test
    fun `an empty section list with a selection returns null`() {
        assertNull(selectedDetectionFrom(emptyList(), selectedId = UUID.randomUUID()))
    }

    @Test
    fun `the detection is found across multiple date sections, not just the first`() {
        val target = detection()
        val sections = listOf(
            DetectionDateSection(date = LocalDate.of(2026, 8, 7), items = listOf(detection())),
            DetectionDateSection(date = LocalDate.of(2026, 8, 8), items = listOf(detection(), target))
        )

        val result = selectedDetectionFrom(sections, selectedId = target.id)

        assertEquals(target, result)
    }
}
