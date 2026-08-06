package hr.sonicpulse.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `a job that never cooperates with cancellation still causes await to return within the grace bound`() = runTest {
        val job = launch {
            withContext(NonCancellable) {
                delay(Long.MAX_VALUE) // ignores the cancel() SubmissionDrain issues after the timeout
            }
        }

        // Reaching this line at all is the assertion: without a bounded second wait, this would
        // hang forever waiting for a job that structurally cannot honor cancellation.
        SubmissionDrain.await(listOf(job), timeoutMillis, graceMillis)

        assertTrue("cancellation was requested even though the job couldn't honor it", job.isCancelled)
        job.cancel() // let the leftover coroutine actually wind down so runTest's own cleanup succeeds
    }

    @Test
    fun `the grace wait itself is bounded even when every active job is uncooperative`() = runTest {
        val jobs = List(3) {
            launch { withContext(NonCancellable) { delay(Long.MAX_VALUE) } }
        }

        SubmissionDrain.await(jobs, timeoutMillis, graceMillis)

        jobs.forEach { assertTrue(it.isCancelled) }
        jobs.forEach { it.cancel() }
    }
}
