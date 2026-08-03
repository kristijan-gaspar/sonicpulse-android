package hr.sonicpulse.app.repository

import hr.sonicpulse.app.domain.model.Detection

/** One page of device history — `nextCursor == null` means there are no more pages. */
data class DetectionPage(
    val items: List<Detection>,
    val nextCursor: Long?
)

/**
 * Remote data access only — no stored paging state, no dedup, no date grouping, no filters, no
 * UI-shaped errors. A single call fetches exactly one page; the caller (DetectionsViewModel, see
 * Chunk 3) owns cursor tracking, merging pages, and everything else about the screen session.
 */
interface DetectionsRepository {
    suspend fun getDetectionsPage(cursor: Long?, limit: Int): DetectionPage
}
