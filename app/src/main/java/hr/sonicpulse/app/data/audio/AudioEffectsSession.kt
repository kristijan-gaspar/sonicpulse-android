package hr.sonicpulse.app.data.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Owns optional audio effects for one capture session.
 *
 * Effects are disabled on a best-effort basis and released when the session ends.
 */
class AudioEffectsSession(audioSessionId: Int) {

    companion object {
        private const val TAG = "AudioEffectsSession"
    }

    private val effects: List<AudioEffect> = listOfNotNull(
        createAndDisable("AutomaticGainControl") {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(audioSessionId)
            } else {
                null
            }
        },
        createAndDisable("NoiseSuppressor") {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)
            } else {
                null
            }
        },
        createAndDisable("AcousticEchoCanceler") {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)
            } else {
                null
            }
        }
    )

    private fun createAndDisable(
        effectName: String,
        create: () -> AudioEffect?
    ): AudioEffect? {
        var effect: AudioEffect? = null

        return try {
            effect = create() ?: return null

            val result = effect.setEnabled(false)

            if (result != AudioEffect.SUCCESS) {
                Log.w(
                    TAG,
                    "$effectName could not be disabled. Result code: $result"
                )
            }

            effect
        } catch (exception: RuntimeException) {
            Log.w(
                TAG,
                "$effectName could not be created or disabled.",
                exception
            )

            releaseSafely(effect)
            null
        }
    }

    fun release() {
        effects.forEach(::releaseSafely)
    }

    private fun releaseSafely(effect: AudioEffect?) {
        try {
            effect?.release()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Audio effect could not be released.", exception)
        }
    }
}
