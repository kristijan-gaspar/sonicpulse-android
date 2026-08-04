package hr.sonicpulse.app.repository

/** Diagnostic counters for submission outcomes (plan §2.9), reset every monitoring session. */
data class SubmissionCounters(
    val droppedNetwork: Int = 0,
    val droppedNoLocation: Int = 0,
    val droppedStaleLocation: Int = 0,
    val droppedInaccurateLocation: Int = 0,
    val droppedLocalStorage: Int = 0,
    /** Monitoring couldn't start, or a runtime location refresh failed, specifically because a
     * required permission was missing — a real current-session diagnostic (Settings §D), never a
     * hardcoded value. Distinct from the location-quality drops above, which assume permission was
     * already granted. */
    val droppedPermission: Int = 0,
    val submissionSucceeded: Int = 0,
    val submissionFailedBadRequest: Int = 0,
    val submissionFailedUnauthorized: Int = 0,
    val submissionRateLimited: Int = 0,
    val submissionFailedClient: Int = 0,
    val submissionFailedServer: Int = 0,
    val submissionFailedUnexpected: Int = 0,
    val cancelled: Int = 0
)
