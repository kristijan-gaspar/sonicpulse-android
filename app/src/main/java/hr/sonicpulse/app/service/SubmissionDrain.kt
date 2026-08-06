package hr.sonicpulse.app.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The wait-then-cancel algorithm behind [MonitoringService.drainSubmissions] — pulled out as a
 * pure function (no Android, no [SubmissionJobTracker]) so it is directly unit-testable with a
 * virtual-time test dispatcher. Has no opinion on what "finishing" means for a job: a submission
 * that finishes on its own within [timeoutMillis] — successfully or not — has already applied its
 * own real result before [await] returns; this only decides how long to wait and which stragglers
 * to cancel once that window has passed.
 */
internal object SubmissionDrain {

    suspend fun await(jobs: List<Job>, timeoutMillis: Long) {
        if (jobs.isEmpty()) return

        withTimeoutOrNull(timeoutMillis) { jobs.joinAll() }

        // Whatever is still active after the bounded wait is genuinely stuck — cancel it so it
        // stops holding its detection Pending forever, then wait (cooperative, but not
        // indefinite: cancellation is expected to be fast) for that cancellation to finish.
        jobs.filter { it.isActive }.forEach { it.cancel() }
        jobs.joinAll()
    }
}
