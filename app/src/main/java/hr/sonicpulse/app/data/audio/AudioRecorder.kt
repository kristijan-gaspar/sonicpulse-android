package hr.sonicpulse.app.data.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import hr.sonicpulse.engine.EngineConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a single active capture session at a time — one [AudioRecord], one dedicated read
 * thread, one [AudioEffectsSession] — per algorithm doc §0.
 *
 * Threading contract: `start()`, `stop()` and `close()` may be called from any thread and
 * synchronize internally. If `start()` fails during setup (unsupported configuration,
 * permission denied, or executor rejection), [onError] is invoked synchronously on the
 * calling thread before `start()` returns. Once a session is running, both [onBlock] and
 * any subsequent [onError] (read failures, unexpected errors) are invoked exclusively from
 * this recorder's dedicated internal capture thread, never the original caller's thread.
 *
 * Lifecycle: `stop()` ends the current session (blocking until its read thread has fully
 * exited) but the same instance can `start()` again afterward. `close()` permanently shuts
 * down the internal executor; `start()` after `close()` throws. Session ownership (the
 * shared `session` field) is only ever cleared by the worker thread's own finalization —
 * never by `stop()` — so a session is never considered "over" until it truly has finished.
 */
class AudioRecorder(
    private val audioManager: AudioManager,
    private val sampleRate: Int = EngineConfig().sampleRate,
    private val blockSize: Int = EngineConfig().blockSize
) : AutoCloseable {

    private class Session(val record: AudioRecord, val effects: AudioEffectsSession) {
        @Volatile var stopRequested = false
        @Volatile var workerThread: Thread? = null
        val cleanedUp = AtomicBoolean(false)
        val shutdownGate = AudioCaptureShutdownGate()
    }

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isClosed = false

    private var session: Session? = null

    fun start(onBlock: (ShortArray) -> Unit, onError: (AudioCaptureError) -> Unit) {
        synchronized(lock) {
            check(!isClosed) { "AudioRecorder is closed and cannot be restarted." }
            check(session == null) { "start() called while a capture session is already running." }

            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferBytes == AudioRecord.ERROR || minBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
                onError(AudioCaptureError.UnsupportedConfiguration)
                return
            }

            val record = createRecord(minBufferBytes, onError) ?: return
            val effects = AudioEffectsSession(record.audioSessionId)

            when (startRecording(record)) {
                StartRecordingResult.Started -> Unit
                StartRecordingResult.PermissionDenied -> {
                    abortSession(record, effects)
                    onError(AudioCaptureError.PermissionDenied)
                    return
                }
                StartRecordingResult.Failed -> {
                    abortSession(record, effects)
                    onError(AudioCaptureError.UnsupportedConfiguration)
                    return
                }
            }

            val newSession = Session(record, effects)
            session = newSession
            try {
                executor.execute { runSession(newSession, onBlock, onError) }
            } catch (e: RejectedExecutionException) {
                newSession.stopRequested = true
                cleanup(newSession)
                if (session === newSession) {
                    session = null
                }
                newSession.shutdownGate.signalFinished()
                onError(AudioCaptureError.Unexpected(e))
            }
        }
    }

    private enum class StartRecordingResult { Started, PermissionDenied, Failed }

    private fun startRecording(record: AudioRecord): StartRecordingResult {
        try {
            record.startRecording()
        } catch (e: SecurityException) {
            return StartRecordingResult.PermissionDenied
        } catch (e: IllegalStateException) {
            return StartRecordingResult.Failed
        }
        return if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            StartRecordingResult.Started
        } else {
            StartRecordingResult.Failed
        }
    }

    /** Safe cleanup for a record that never successfully entered a running session. */
    private fun abortSession(record: AudioRecord, effects: AudioEffectsSession) {
        try {
            record.stop()
        } catch (e: RuntimeException) {
            // Not recording, or another platform quirk; must not prevent release() below.
        }
        try {
            record.release()
        } catch (e: RuntimeException) {
            // Best-effort: must not prevent effects cleanup below.
        }
        effects.release()
    }

    /**
     * Tries [AudioSourceSelector]'s primary choice first; if the device claims `UNPROCESSED`
     * support but construction or [AudioRecord.STATE_INITIALIZED] validation fails, falls
     * back to `VOICE_RECOGNITION` once (algorithm doc §0). A [SecurityException] is never
     * retried with a different source — it always means [AudioCaptureError.PermissionDenied].
     */
    private fun createRecord(minBufferBytes: Int, onError: (AudioCaptureError) -> Unit): AudioRecord? {
        val bufferSizeBytes = AudioBufferSizeCalculator.calculate(minBufferBytes, blockSize)
        val supportsUnprocessed = supportsUnprocessed()

        val primarySource = AudioSourceSelector.select(supportsUnprocessed)
        when (val primary = attemptCreate(primarySource, bufferSizeBytes)) {
            is CreateAttempt.Success -> return primary.record
            CreateAttempt.PermissionDenied -> {
                onError(AudioCaptureError.PermissionDenied)
                return null
            }
            CreateAttempt.Unsupported -> {
                if (!supportsUnprocessed) {
                    onError(AudioCaptureError.UnsupportedConfiguration)
                    return null
                }
                // Primary attempt was UNPROCESSED and failed; fall back to VOICE_RECOGNITION once.
            }
        }

        val fallbackSource = AudioSourceSelector.select(supportsUnprocessed = false)
        return when (val fallback = attemptCreate(fallbackSource, bufferSizeBytes)) {
            is CreateAttempt.Success -> fallback.record
            CreateAttempt.PermissionDenied -> {
                onError(AudioCaptureError.PermissionDenied)
                null
            }
            CreateAttempt.Unsupported -> {
                onError(AudioCaptureError.UnsupportedConfiguration)
                null
            }
        }
    }

    private sealed interface CreateAttempt {
        data class Success(val record: AudioRecord) : CreateAttempt
        data object PermissionDenied : CreateAttempt
        data object Unsupported : CreateAttempt
    }

    private fun attemptCreate(source: Int, bufferSizeBytes: Int): CreateAttempt {
        val record = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeBytes)
        } catch (e: SecurityException) {
            return CreateAttempt.PermissionDenied
        } catch (e: IllegalArgumentException) {
            return CreateAttempt.Unsupported
        }

        return if (record.state == AudioRecord.STATE_INITIALIZED) {
            CreateAttempt.Success(record)
        } else {
            record.release()
            CreateAttempt.Unsupported
        }
    }

    private fun supportsUnprocessed(): Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    private fun runSession(
        session: Session,
        onBlock: (ShortArray) -> Unit,
        onError: (AudioCaptureError) -> Unit
    ) {
        session.workerThread = Thread.currentThread()
        val accumulator = AudioBlockAccumulator(blockSize)
        val readBuffer = ShortArray(blockSize)

        try {
            while (!session.stopRequested) {
                val samplesRead = session.record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (samplesRead < 0) {
                    if (!session.stopRequested) {
                        onError(AudioCaptureError.ReadFailure(samplesRead))
                    }
                    break
                }
                accumulator.accumulate(readBuffer, samplesRead, onBlock)
            }
        } catch (e: SecurityException) {
            // e.g. RECORD_AUDIO permission revoked mid-capture.
            if (!session.stopRequested) {
                onError(AudioCaptureError.PermissionDenied)
            }
        } catch (e: RuntimeException) {
            if (!session.stopRequested) {
                onError(AudioCaptureError.Unexpected(e))
            }
        } finally {
            // Nested try/finally: even if cleanup() unexpectedly throws, the completion
            // signal (ownership clear + countDown) must never be skipped.
            try {
                cleanup(session)
            } finally {
                synchronized(lock) {
                    if (this.session === session) {
                        this.session = null
                    }
                    // Last operation in this block: a new start() can only proceed once
                    // this synchronized block exits, so by then the latch is already open
                    // too — the previous worker is guaranteed to have fully completed.
                    session.shutdownGate.signalFinished()
                }
            }
        }
    }

    private fun cleanup(session: Session) {
        if (!session.cleanedUp.compareAndSet(false, true)) return

        try {
            session.record.stop()
        } catch (e: RuntimeException) {
            // Already stopped, never started, or another platform quirk — must not
            // prevent release() or effects cleanup below.
        }
        try {
            session.record.release()
        } catch (e: RuntimeException) {
            // Best-effort: must not prevent effects cleanup below.
        }
        session.effects.release()
    }

    /**
     * Ends the current session, if any. Waits up to [STOP_TIMEOUT_MILLIS] for the dedicated
     * read thread to fully exit — unless called from that same thread (e.g. from within
     * [onError] triggered by a read failure), in which case waiting for itself would deadlock.
     * The wait is bounded, never indefinite: an OEM [AudioRecord.read] or [AudioRecord.stop]
     * that never returns must not hang the caller forever. Safe to call multiple times, and
     * safe to call when no session is running. Does not itself release resources or clear
     * session ownership — that is always the worker's own responsibility, so a session is
     * never considered finished until it truly has.
     *
     * Callers must never invoke this from the Android main thread directly — the bounded wait
     * still blocks whichever thread calls it, so callers are expected to do so from a
     * background dispatcher/thread (see [MonitoringService][hr.sonicpulse.app.service.MonitoringService]).
     */
    fun stop() {
        val current: Session
        synchronized(lock) {
            current = session ?: return
            current.stopRequested = true
        }
        try {
            current.record.stop() // unblocks a thread parked in READ_BLOCKING
        } catch (e: RuntimeException) {
            // Already stopped, never started, or another platform quirk — the read loop will
            // exit on its own via stopRequested, and must not prevent the wait/cleanup below.
            Log.w(TAG, "AudioRecord.stop() failed while requesting shutdown: ${e.javaClass.simpleName}")
        }
        when (current.shutdownGate.awaitUpTo(STOP_TIMEOUT_MILLIS, Thread.currentThread(), current.workerThread)) {
            AudioCaptureShutdownGate.AwaitResult.TIMED_OUT ->
                Log.w(TAG, "Timed out after ${STOP_TIMEOUT_MILLIS}ms waiting for the capture thread to finish")
            AudioCaptureShutdownGate.AwaitResult.INTERRUPTED ->
                Log.w(TAG, "Interrupted while waiting for the capture thread to finish")
            AudioCaptureShutdownGate.AwaitResult.FINISHED,
            AudioCaptureShutdownGate.AwaitResult.CALLED_FROM_WORKER_THREAD -> Unit
        }
    }

    /** Permanently stops capture and releases the internal executor. Idempotent. */
    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }
        stop()
        executor.shutdown()
    }

    private companion object {
        private const val TAG = "AudioRecorder"

        /** Bounds [stop]'s wait for the capture thread — long enough for a normal
         * read()/stop() cycle to unwind, short enough to never look like a hang to the caller. */
        private const val STOP_TIMEOUT_MILLIS = 3_000L
    }
}
