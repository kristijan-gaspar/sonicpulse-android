package hr.sonicpulse.app.service

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionJobTrackerTest {

    @Test
    fun `a registered job appears in the snapshot`() {
        val tracker = SubmissionJobTracker()
        val job = Job()

        tracker.register(UUID.randomUUID(), job)

        assertEquals(listOf(job), tracker.snapshot())
    }

    @Test
    fun `snapshot includes every distinct registered job`() {
        val tracker = SubmissionJobTracker()
        val first = Job()
        val second = Job()

        tracker.register(UUID.randomUUID(), first)
        tracker.register(UUID.randomUUID(), second)

        assertEquals(setOf(first, second), tracker.snapshot().toSet())
    }

    @Test
    fun `a job removes itself from the snapshot once it completes`() {
        val tracker = SubmissionJobTracker()
        val job: CompletableJob = Job()
        tracker.register(UUID.randomUUID(), job)

        job.complete()

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `a still-active job remains in the snapshot`() {
        val tracker = SubmissionJobTracker()
        val job = Job()

        tracker.register(UUID.randomUUID(), job)

        assertEquals(1, tracker.snapshot().size)
        job.cancel()
    }

    @Test
    fun `after close, a new register call is a no-op`() {
        val tracker = SubmissionJobTracker()
        tracker.close()

        tracker.register(UUID.randomUUID(), Job())

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `close does not affect jobs already registered`() {
        val tracker = SubmissionJobTracker()
        val job = Job()
        tracker.register(UUID.randomUUID(), job)

        tracker.close()

        assertEquals(listOf(job), tracker.snapshot())
        job.cancel()
    }

    @Test
    fun `close is idempotent`() {
        val tracker = SubmissionJobTracker()

        tracker.close()
        tracker.close()

        tracker.register(UUID.randomUUID(), Job())
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is a point-in-time copy, not a live view`() {
        val tracker = SubmissionJobTracker()
        val first = Job()
        tracker.register(UUID.randomUUID(), first)

        val snapshot = tracker.snapshot()
        tracker.register(UUID.randomUUID(), Job())

        assertEquals(1, snapshot.size)
        assertEquals(2, tracker.snapshot().size)
    }

    @Test
    fun `concurrent register and completion from different threads never corrupts the map`() {
        val tracker = SubmissionJobTracker()
        val jobCount = 500
        val jobs = (0 until jobCount).map { CompletableJobPair(UUID.randomUUID(), Job()) }
        val executor = Executors.newFixedThreadPool(8)
        val allRegistered = CountDownLatch(jobCount)

        jobs.forEach { (id, job) ->
            executor.submit {
                tracker.register(id, job)
                allRegistered.countDown()
            }
        }
        assertTrue(allRegistered.await(5, TimeUnit.SECONDS))
        assertEquals(jobCount, tracker.snapshot().size)

        val allCompleted = CountDownLatch(jobCount)
        jobs.forEach { (_, job) ->
            executor.submit {
                job.complete()
                allCompleted.countDown()
            }
        }
        assertTrue(allCompleted.await(5, TimeUnit.SECONDS))

        // invokeOnCompletion handlers run synchronously as each job completes, but this test
        // thread isn't one of the completing threads — give the last handler(s) a moment to run.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (tracker.snapshot().isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(tracker.snapshot().isEmpty())

        executor.shutdown()
    }

    private data class CompletableJobPair(val id: UUID, val job: CompletableJob)
}
