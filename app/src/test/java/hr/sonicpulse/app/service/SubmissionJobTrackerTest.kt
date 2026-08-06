package hr.sonicpulse.app.service

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionJobTrackerTest {

    @Test
    fun `a registered job appears in the snapshot`() {
        val tracker = SubmissionJobTracker()
        val job = Job()

        val accepted = tracker.register(UUID.randomUUID(), job)

        assertTrue(accepted)
        assertEquals(listOf(job), tracker.closeAndSnapshot())
        job.cancel()
    }

    @Test
    fun `snapshot includes every distinct registered job`() {
        val tracker = SubmissionJobTracker()
        val first = Job()
        val second = Job()

        tracker.register(UUID.randomUUID(), first)
        tracker.register(UUID.randomUUID(), second)

        assertEquals(setOf(first, second), tracker.closeAndSnapshot().toSet())
        first.cancel()
        second.cancel()
    }

    @Test
    fun `a job removes itself from a later snapshot once it completes`() {
        val tracker = SubmissionJobTracker()
        val job: CompletableJob = Job()
        tracker.register(UUID.randomUUID(), job)

        job.complete()

        assertTrue(tracker.closeAndSnapshot().isEmpty())
    }

    @Test
    fun `after closeAndSnapshot, a new register call is rejected`() {
        val tracker = SubmissionJobTracker()
        tracker.closeAndSnapshot()

        val accepted = tracker.register(UUID.randomUUID(), Job())

        assertFalse(accepted)
    }

    @Test
    fun `closeAndSnapshot does not affect jobs already registered`() {
        val tracker = SubmissionJobTracker()
        val job = Job()
        tracker.register(UUID.randomUUID(), job)

        val snapshot = tracker.closeAndSnapshot()

        assertEquals(listOf(job), snapshot)
        job.cancel()
    }

    @Test
    fun `closeAndSnapshot is idempotent`() {
        val tracker = SubmissionJobTracker()

        val first = tracker.closeAndSnapshot()
        val second = tracker.closeAndSnapshot()

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertFalse(tracker.register(UUID.randomUUID(), Job()))
    }

    @Test
    fun `every accepted job is either completed or included in the final snapshot`() {
        val tracker = SubmissionJobTracker()
        val completesBeforeClose: CompletableJob = Job()
        val stillActiveAtClose = Job()
        tracker.register(UUID.randomUUID(), completesBeforeClose)
        tracker.register(UUID.randomUUID(), stillActiveAtClose)

        completesBeforeClose.complete()
        val snapshot = tracker.closeAndSnapshot()

        // completesBeforeClose already resolved on its own (not "in" the snapshot, but not lost —
        // its own completion is what removed it); stillActiveAtClose must be present.
        assertEquals(listOf(stillActiveAtClose), snapshot)
        stillActiveAtClose.cancel()
    }

    @Test
    fun `concurrent register and closeAndSnapshot account for every accepted job exactly once`() {
        val tracker = SubmissionJobTracker()
        val jobCount = 200
        val jobs = List(jobCount) {
            UUID.randomUUID() to Job()
        }

        val executor = Executors.newFixedThreadPool(16)
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(jobCount + 1)

        val acceptedJobs =
            java.util.Collections.synchronizedList(mutableListOf<Job>())

        val snapshotReference =
            java.util.concurrent.atomic.AtomicReference<List<Job>>(emptyList())

        fun submitRegistration(id: UUID, job: Job) {
            executor.submit {
                try {
                    startGate.await()

                    if (tracker.register(id, job)) {
                        acceptedJobs += job
                    }
                } finally {
                    finished.countDown()
                }
            }
        }

        jobs.take(8).forEach { (id, job) ->
            submitRegistration(id, job)
        }

        executor.submit {
            try {
                startGate.await()
                snapshotReference.set(tracker.closeAndSnapshot())
            } finally {
                finished.countDown()
            }
        }

        jobs.drop(8).forEach { (id, job) ->
            submitRegistration(id, job)
        }

        startGate.countDown()

        assertTrue(
            "Concurrent operations did not finish in time",
            finished.await(5, TimeUnit.SECONDS)
        )

        val snapshot = snapshotReference.get()

        assertEquals(acceptedJobs.toSet(), snapshot.toSet())
        assertEquals(acceptedJobs.size, snapshot.size)

        jobs.forEach { (_, job) ->
            job.cancel()
        }

        executor.shutdownNow()
    }

    @Test
    fun `a rejected job never has its completion handler fire against the tracker`() {
        // Registering after close must not throw or corrupt state even when the caller still
        // completes/cancels the rejected job independently afterward.
        val tracker = SubmissionJobTracker()
        tracker.closeAndSnapshot()
        val job = Job()

        val accepted = tracker.register(UUID.randomUUID(), job)
        job.cancel()

        assertFalse(accepted)
        assertTrue(tracker.closeAndSnapshot().isEmpty())
    }

    @Test
    fun `completion of an older job does not remove a newer job registered with the same id`() {
        val tracker = SubmissionJobTracker()
        val localEventId = UUID.randomUUID()

        val olderJob: CompletableJob = Job()
        val newerJob: CompletableJob = Job()

        assertTrue(tracker.register(localEventId, olderJob))
        assertTrue(tracker.register(localEventId, newerJob))

        /*
         * Završetak starijeg Joba ne smije ukloniti noviji Job
         * koji je u međuvremenu registriran pod istim ID-em.
         */
        olderJob.complete()

        val snapshot = tracker.closeAndSnapshot()

        assertEquals(listOf(newerJob), snapshot)

        newerJob.complete()
    }
}
