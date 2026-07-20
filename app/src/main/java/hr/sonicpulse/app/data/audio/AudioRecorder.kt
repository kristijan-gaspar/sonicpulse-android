package hr.sonicpulse.app.data.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import hr.sonicpulse.engine.EngineConfig
import java.util.concurrent.CountDownLatch
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
 * down the internal executor; `start()` after `close()` throws.
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
        val finished = CountDownLatch(1)
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
            val newSession = Session(record, effects)

            if (!startRecording(record)) {
                effects.release()
                record.release()
                onError(AudioCaptureError.UnsupportedConfiguration)
                return
            }

            session = newSession
            try {
                executor.execute { runSession(newSession, onBlock, onError) }
            } catch (e: RejectedExecutionException) {
                session = null
                effects.release()
                record.release()
                onError(AudioCaptureError.Unexpected(e))
            }
        }
    }

    private fun startRecording(record: AudioRecord): Boolean {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            return false
        }
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
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
        } catch (e: RuntimeException) {
            if (!session.stopRequested) {
                onError(AudioCaptureError.Unexpected(e))
            }
        } finally {
            cleanup(session)
            session.finished.countDown()
        }
    }

    private fun cleanup(session: Session) {
        if (!session.cleanedUp.compareAndSet(false, true)) return

        try {
            session.record.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or never started recording; nothing to clean up here.
        }
        session.record.release()
        session.effects.release()
    }

    /**
     * Ends the current session, if any. Blocks until the dedicated read thread has fully
     * exited — unless called from that same thread (e.g. from within [onError] triggered
     * by a read failure), in which case waiting for itself would deadlock. Safe to call
     * multiple times, and safe to call when no session is running.
     */
    fun stop() {
        synchronized(lock) {
            val current = session ?: return
            current.stopRequested = true
            try {
                current.record.stop() // unblocks a thread parked in READ_BLOCKING
            } catch (e: IllegalStateException) {
                // Already stopped; the read loop will exit on its own.
            }
            if (Thread.currentThread() != current.workerThread) {
                current.finished.await()
            }
            cleanup(current)
            session = null
        }
    }

    /** Permanently stops capture and releases the internal executor. Idempotent. */
    override fun close() {
        stop()
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }
        executor.shutdown()
    }
}
