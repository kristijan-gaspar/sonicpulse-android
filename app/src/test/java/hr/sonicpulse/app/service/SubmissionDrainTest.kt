package hr.sonicpulse.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionDrainTest {

    private val timeoutMillis = 3_000L
    private val graceMillis = 500L

    @Test
    fun `an empty job list returns immediately without throwing`() = runTest {
        SubmissionDrain.await(emptyList(), timeoutMillis, graceMillis)
    }

    @Test
    fun `a job that finishes well within the timeout is left alone, not cancelled`() = runTest {
        var applied = false
        val job = launch {
            delay(100)
            applied = true
        }

        SubmissionDrain.await(listOf(job), timeoutMillis, graceMillis)

        assertTrue("job's own result must have been applied", applied)
        assertFalse("a job that finished on its own must never be reported as cancelled", job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a job that never finishes is cancelled once the timeout elapses`() = runTest {
        val job = launch {
            delay(Long.MAX_VALUE) // never completes on its own — models a hung network call
        }

        SubmissionDrain.await(listOf(job), timeoutMillis, graceMillis)

        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `only the jobs still active when the timeout elapses are cancelled, finished ones are untouched`() = runTest {
        val finishedEarly = launch { delay(100) }
        val stillRunning = launch { delay(Long.MAX_VALUE) }

        SubmissionDrain.await(listOf(finishedEarly, stillRunning), timeoutMillis, graceMillis)

        assertFalse(finishedEarly.isCancelled)
        assertTrue(stillRunning.isCancelled)
    }

    @Test
    fun `a job finishing just before the timeout is not spuriously reported as cancelled`() = runTest {
        val job = launch { delay(timeoutMillis - 1) }

        SubmissionDrain.await(listOf(job), timeoutMillis, graceMillis)

        assertFalse(job.isCancelled)
    }

    @Test
    fun `multiple jobs finishing at different points before the timeout are all left uncancelled`() = runTest {
        var firstApplied = false
        var secondApplied = false
        val first = launch { delay(50); firstApplied = true }
        val second = launch { delay(500); secondApplied = true }

        SubmissionDrain.await(listOf(first, second), timeoutMillis, graceMillis)

        assertTrue(firstApplied)
        assertTrue(secondApplied)
        assertFalse(first.isCancelled)
        assertFalse(second.isCancelled)
    }

    @Test
    fun `an uncooperative job cannot make the grace wait unbounded`() = runTest {
        val job = NonCooperativeJob()
        val startedAt = testScheduler.currentTime

        SubmissionDrain.await(
            jobs = listOf(job),
            timeoutMillis = timeoutMillis,
            cancellationGraceMillis = graceMillis
        )

        assertTrue(job.cancellationRequested)
        assertTrue(job.isActive)

        assertEquals(
            timeoutMillis + graceMillis,
            testScheduler.currentTime - startedAt
        )

        job.finish()
    }

    @Test
    fun `multiple uncooperative jobs cannot make the grace wait unbounded`() = runTest {
        val jobs = List(3) {
            NonCooperativeJob()
        }

        val startedAt = testScheduler.currentTime

        SubmissionDrain.await(
            jobs = jobs,
            timeoutMillis = timeoutMillis,
            cancellationGraceMillis = graceMillis
        )

        jobs.forEach { job ->
            assertTrue(job.cancellationRequested)
            assertTrue(job.isActive)
        }

        assertEquals(
            timeoutMillis + graceMillis,
            testScheduler.currentTime - startedAt
        )

        jobs.forEach { job ->
            job.finish()
        }
    }
    private class NonCooperativeJob(
        private val delegate: CompletableJob = Job()
    ) : Job by delegate {

        var cancellationRequested: Boolean = false
            private set

        /*
         * Namjerno bilježimo cancellation, ali ne završavamo delegate.
         * Time simuliramo posao koji ne surađuje s otkazivanjem.
         */
        override fun cancel(cause: CancellationException?) {
            cancellationRequested = true
        }

        fun finish() {
            delegate.complete()
        }
    }

}
