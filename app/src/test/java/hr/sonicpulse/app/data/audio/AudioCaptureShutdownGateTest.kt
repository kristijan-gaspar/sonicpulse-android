package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureShutdownGateTest {

    @Test
    fun `already-finished worker returns FINISHED immediately`() {
        val gate = AudioCaptureShutdownGate()
        gate.signalFinished()

        val result = gate.awaitUpTo(1_000, Thread.currentThread(), workerThread = Thread())

        assertEquals(AudioCaptureShutdownGate.AwaitResult.FINISHED, result)
    }

    @Test
    fun `a worker that signals shortly after being asked returns FINISHED within the timeout`() {
        val gate = AudioCaptureShutdownGate()
        val worker = Thread {
            Thread.sleep(50)
            gate.signalFinished()
        }
        worker.start()

        val result = gate.awaitUpTo(2_000, Thread.currentThread(), worker)

        assertEquals(AudioCaptureShutdownGate.AwaitResult.FINISHED, result)
        worker.join()
    }

    @Test
    fun `a worker that never signals times out instead of waiting forever`() {
        val gate = AudioCaptureShutdownGate()
        val neverSignals = Thread { Thread.sleep(10_000) }
        neverSignals.isDaemon = true
        neverSignals.start()

        val start = System.nanoTime()
        val result = gate.awaitUpTo(100, Thread.currentThread(), neverSignals)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(AudioCaptureShutdownGate.AwaitResult.TIMED_OUT, result)
        assertTrue("expected a bounded wait, took ${elapsedMillis}ms", elapsedMillis < 2_000)
    }

    @Test
    fun `calling from the worker thread itself never waits, avoiding a deadlock`() {
        val gate = AudioCaptureShutdownGate()
        val currentThread = Thread.currentThread()

        val start = System.nanoTime()
        val result = gate.awaitUpTo(10_000, currentThread, workerThread = currentThread)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(AudioCaptureShutdownGate.AwaitResult.CALLED_FROM_WORKER_THREAD, result)
        assertTrue("expected an immediate return, took ${elapsedMillis}ms", elapsedMillis < 1_000)
    }

    @Test
    fun `repeated awaits after finishing are all idempotent`() {
        val gate = AudioCaptureShutdownGate()
        gate.signalFinished()
        gate.signalFinished() // extra calls must be safe no-ops

        val first = gate.awaitUpTo(500, Thread.currentThread(), workerThread = Thread())
        val second = gate.awaitUpTo(500, Thread.currentThread(), workerThread = Thread())

        assertEquals(AudioCaptureShutdownGate.AwaitResult.FINISHED, first)
        assertEquals(AudioCaptureShutdownGate.AwaitResult.FINISHED, second)
    }

    @Test
    fun `an interrupted wait restores the interrupted flag and reports INTERRUPTED`() {
        val gate = AudioCaptureShutdownGate()
        var observedResult: AudioCaptureShutdownGate.AwaitResult? = null
        var interruptedFlagAfterAwait: Boolean? = null

        val waiter = Thread {
            observedResult = gate.awaitUpTo(10_000, Thread.currentThread(), workerThread = Thread())
            interruptedFlagAfterAwait = Thread.currentThread().isInterrupted
        }
        waiter.start()
        Thread.sleep(100) // let it enter the wait
        waiter.interrupt()
        waiter.join(2_000)

        assertEquals(AudioCaptureShutdownGate.AwaitResult.INTERRUPTED, observedResult)
        assertEquals(true, interruptedFlagAfterAwait)
    }
}
