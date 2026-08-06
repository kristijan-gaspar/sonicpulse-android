package hr.sonicpulse.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionDrainTest {

    private val timeoutMillis = 3_000L

    @Test
    fun `an empty job list returns immediately without throwing`() = runTest {
        SubmissionDrain.await(emptyList(), timeoutMillis)
    }

    @Test
    fun `a job that finishes well within the timeout is left alone, not cancelled`() = runTest {
        var applied = false
        val job = launch {
            delay(100)
            applied = true
        }

        SubmissionDrain.await(listOf(job), timeoutMillis)

        assertTrue("job's own result must have been applied", applied)
        assertFalse("a job that finished on its own must never be reported as cancelled", job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a job that never finishes is cancelled once the timeout elapses`() = runTest {
        val job = launch {
            delay(Long.MAX_VALUE) // never completes on its own — models a hung network call
        }

        SubmissionDrain.await(listOf(job), timeoutMillis)

        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `only the jobs still active when the timeout elapses are cancelled, finished ones are untouched`() = runTest {
        val finishedEarly = launch { delay(100) }
        val stillRunning = launch { delay(Long.MAX_VALUE) }

        SubmissionDrain.await(listOf(finishedEarly, stillRunning), timeoutMillis)

        assertFalse(finishedEarly.isCancelled)
        assertTrue(stillRunning.isCancelled)
    }

    @Test
    fun `a job finishing just before the timeout is not spuriously reported as cancelled`() = runTest {
        val job = launch { delay(timeoutMillis - 1) }

        SubmissionDrain.await(listOf(job), timeoutMillis)

        assertFalse(job.isCancelled)
    }

    @Test
    fun `multiple jobs finishing at different points before the timeout are all left uncancelled`() = runTest {
        var firstApplied = false
        var secondApplied = false
        val first = launch { delay(50); firstApplied = true }
        val second = launch { delay(500); secondApplied = true }

        SubmissionDrain.await(listOf(first, second), timeoutMillis)

        assertTrue(firstApplied)
        assertTrue(secondApplied)
        assertFalse(first.isCancelled)
        assertFalse(second.isCancelled)
    }
}
