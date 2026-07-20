package hr.sonicpulse.app.data.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import hr.sonicpulse.engine.EngineConfig
import java.util.concurrent.Executors

/**
 * Owns a single [AudioRecord] instance and a dedicated read thread, per algorithm doc §0.
 * [onBlock] and [onError] passed to [start] are always invoked from that dedicated thread,
 * never the caller's thread.
 */
class AudioRecorder(
    private val audioManager: AudioManager,
    private val sampleRate: Int = EngineConfig().sampleRate,
    private val blockSize: Int = EngineConfig().blockSize
) {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isRunning = false

    private var audioRecord: AudioRecord? = null

    fun start(onBlock: (ShortArray) -> Unit, onError: (AudioCaptureError) -> Unit) {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes == AudioRecord.ERROR || minBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
            onError(AudioCaptureError.UnsupportedConfiguration)
            return
        }

        val record = createAudioRecord(minBufferBytes)
        if (record == null) {
            onError(AudioCaptureError.PermissionDenied)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(AudioCaptureError.UnsupportedConfiguration)
            return
        }

        AudioEffectsDisabler.disableIfAvailable(record.audioSessionId)

        audioRecord = record
        isRunning = true
        record.startRecording()
        executor.execute { runReadLoop(record, onBlock, onError) }
    }

    private fun createAudioRecord(minBufferBytes: Int): AudioRecord? {
        val source = AudioSourceSelector.select(supportsUnprocessed())
        val bufferSizeBytes = AudioBufferSizeCalculator.calculate(minBufferBytes, blockSize)

        return try {
            AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        } catch (e: SecurityException) {
            null
        }
    }

    private fun supportsUnprocessed(): Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    private fun runReadLoop(
        record: AudioRecord,
        onBlock: (ShortArray) -> Unit,
        onError: (AudioCaptureError) -> Unit
    ) {
        val accumulator = AudioBlockAccumulator(blockSize)
        val readBuffer = ShortArray(blockSize)

        while (isRunning) {
            val samplesRead = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
            if (samplesRead < 0) {
                isRunning = false
                onError(AudioCaptureError.ReadFailure(samplesRead))
                break
            }
            accumulator.accumulate(readBuffer, samplesRead, onBlock)
        }
    }

    fun stop() {
        isRunning = false
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }
}