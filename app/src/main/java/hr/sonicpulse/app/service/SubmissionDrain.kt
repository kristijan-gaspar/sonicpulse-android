package hr.sonicpulse.app.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The wait-then-cancel algorithm behind [MonitoringService]'s submission drain — pulled out as a
 * pure function (no Android, no [SubmissionJobTracker]) so it is directly unit-testable with a
 * virtual-time test dispatcher. Has no opinion on what "finishing" means for a job: a submission
 * that finishes on its own within [timeoutMillis] — successfully or not — has already applied its
 * own real result before [await] returns; this only decides how long to wait and which stragglers
 * to cancel once that window has passed.
 *
 * Fully bounded end to end, at [timeoutMillis] + [cancellationGraceMillis]: the first
 * [withTimeoutOrNull] bounds the normal-completion wait, and the second bounds the wait for
 * cancellation to actually take effect — [await] always returns within that combined ceiling even
 * if a job never cooperates with cancellation at all.
 */
internal object SubmissionDrain {

    suspend fun await(jobs: List<Job>, timeoutMillis: Long, cancellationGraceMillis: Long) {
        if (jobs.isEmpty()) return

        withTimeoutOrNull(timeoutMillis) { jobs.joinAll() }

        val stillActive = jobs.filter { it.isActive }
        if (stillActive.isEmpty()) return

        // Whatever is still active after the bounded wait is genuinely stuck — cancel it so it
        // stops holding its detection Pending forever, then wait, itself bounded, for that
        // cancellation to actually take effect. A job that never cooperates with cancellation
        // (e.g. blocked in non-cancellable I/O) still causes await() to return once this second
        // bound elapses — never an unbounded wait.
        stillActive.forEach { it.cancel() }
        withTimeoutOrNull(cancellationGraceMillis) { stillActive.joinAll() }
    }
}
