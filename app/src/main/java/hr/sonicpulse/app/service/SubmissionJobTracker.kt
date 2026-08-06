package hr.sonicpulse.app.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * Thread-safe registry of in-flight submission jobs, keyed by `SessionDetection.localEventId`.
 * [register] is called from wherever a submission coroutine is launched (in practice, the audio
 * capture thread — see [MonitoringService]); a job removes itself via [Job.invokeOnCompletion],
 * which can run on whatever dispatcher completed it. That's genuine concurrent access from more
 * than one thread, hence [ConcurrentHashMap] rather than a plain [MutableMap].
 *
 * Exists to fix a Stop/submission race: marking every still-`Pending` detection `Cancelled` the
 * instant Stop is pressed can race a submission whose HTTP response is about to arrive — this
 * tracker lets the service wait, briefly and asynchronously, for genuinely in-flight jobs to
 * finish (or be cancelled) before that happens, so a submission that already succeeded is never
 * overwritten by a stale `Cancelled`.
 */
class SubmissionJobTracker {

    private val jobs = ConcurrentHashMap<UUID, Job>()

    @Volatile
    private var closed = false

    /** No-op once [close] has been called — shutdown has begun, so a new submission started
     * after that point must not be waited for (there would be nothing left to wait with it). */
    fun register(localEventId: UUID, job: Job) {
        if (closed) return
        jobs[localEventId] = job
        job.invokeOnCompletion { jobs.remove(localEventId, job) }
    }

    /** A concurrency-safe point-in-time copy of the currently tracked jobs — not a live view. */
    fun snapshot(): List<Job> = jobs.values.toList()

    /** Stops accepting new [register] calls. Idempotent, and safe to call with jobs still
     * in-flight — closing does not itself cancel or wait for anything. */
    fun close() {
        closed = true
    }
}
