package hr.sonicpulse.app.service

import java.util.UUID
import kotlinx.coroutines.Job

/**
 * Thread-safe registry of in-flight submission jobs, keyed by `SessionDetection.localEventId`.
 * [register] is called from wherever a submission coroutine is created (in practice, the audio
 * capture thread — see [MonitoringService]); a job removes itself via [Job.invokeOnCompletion],
 * which can run on whatever dispatcher completed it. That's genuine concurrent access from more
 * than one thread, so every operation — including a job removing itself on completion — runs
 * under [lock]: "is registration open," "insert," "close," and "snapshot" are one serialized
 * mechanism, not four independent checks that could interleave.
 *
 * Exists to fix a Stop/submission race: marking every still-`Pending` detection `Cancelled` the
 * instant Stop is pressed can race a submission whose HTTP response is about to arrive — this
 * tracker lets the service wait, briefly and asynchronously, for genuinely in-flight jobs to
 * finish (or be cancelled) before that happens, so a submission that already succeeded is never
 * overwritten by a stale `Cancelled`.
 */
class SubmissionJobTracker {

    private val lock = Any()
    private val jobs = HashMap<UUID, Job>()
    private var closed = false

    /**
     * Registers [job] and returns `true` if registration was still open — `false` (a no-op) once
     * [closeAndSnapshot] has already run, meaning shutdown has begun and there is nothing left to
     * wait for this job with. The completion handler that removes [job] once it finishes is
     * attached under the same lock as the insert, so "insert" and "will remove itself on
     * completion" are never observably separate steps.
     */
    fun register(localEventId: UUID, job: Job): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        jobs[localEventId] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[localEventId] === job) {
                    jobs.remove(localEventId)
                }
            }
        }
        true
    }

    /**
     * Atomically closes registration (every subsequent [register] call returns `false`) and
     * returns a point-in-time copy of every job still tracked at that instant — never a separate
     * close-then-snapshot pair that could let a job register or complete in between. Idempotent:
     * calling this again once already closed simply returns whatever (if anything) is still
     * tracked, without reopening or double-closing anything.
     */
    fun closeAndSnapshot(): List<Job> = synchronized(lock) {
        closed = true
        jobs.values.toList()
    }
}
