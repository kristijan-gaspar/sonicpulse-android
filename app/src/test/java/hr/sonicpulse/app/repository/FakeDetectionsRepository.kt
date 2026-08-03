package hr.sonicpulse.app.repository

class FakeDetectionsRepository : DetectionsRepository {

    /** Keyed by the cursor a call was made with — lets a test configure what each page returns. */
    var pages: Map<Long?, DetectionPage> = emptyMap()
    var throwOnGetPage: Throwable? = null
    val requestedCursors = mutableListOf<Long?>()

    override suspend fun getDetectionsPage(cursor: Long?, limit: Int): DetectionPage {
        requestedCursors += cursor
        throwOnGetPage?.let { throw it }
        return pages[cursor] ?: DetectionPage(items = emptyList(), nextCursor = null)
    }
}
