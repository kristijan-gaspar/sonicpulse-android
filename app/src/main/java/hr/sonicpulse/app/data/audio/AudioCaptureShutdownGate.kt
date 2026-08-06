package hr.sonicpulse.app.data.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bounds how long a caller waits for the capture worker thread to finish — never indefinitely,
 * and never at all if the caller *is* the worker thread (waiting for itself would deadlock).
 * Wraps a single-count [CountDownLatch]; pure and Android-free so it is directly unit-testable
 * with real threads, unlike [android.media.AudioRecord] itself.
 */
class AudioCaptureShutdownGate {

    private val latch = CountDownLatch(1)

    enum class AwaitResult { FINISHED, TIMED_OUT, INTERRUPTED, CALLED_FROM_WORKER_THREAD }

    /** Signals that the worker thread has finished. Idempotent — a [CountDownLatch] can only
     * reach zero once, so repeated calls are no-ops. */
    fun signalFinished() {
        latch.countDown()
    }

    /**
     * Waits up to [timeoutMillis] for [signalFinished]. If [callingThread] is [workerThread],
     * returns [AwaitResult.CALLED_FROM_WORKER_THREAD] immediately without waiting at all. An
     * interrupted wait restores the calling thread's interrupted flag and reports
     * [AwaitResult.INTERRUPTED] — never silently swallowed, never rethrown past this boundary.
     */
    fun awaitUpTo(timeoutMillis: Long, callingThread: Thread, workerThread: Thread?): AwaitResult {
        if (callingThread === workerThread) {
            return AwaitResult.CALLED_FROM_WORKER_THREAD
        }
        return try {
            if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                AwaitResult.FINISHED
            } else {
                AwaitResult.TIMED_OUT
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AwaitResult.INTERRUPTED
        }
    }
}
