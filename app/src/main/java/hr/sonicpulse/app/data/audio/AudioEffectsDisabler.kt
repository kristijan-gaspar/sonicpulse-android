package hr.sonicpulse.app.data.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * Disables signal-processing effects that would corrupt dBFS/crest measurements
 * (algorithm doc §0) — only takes effect where the platform actually supports them.
 */
object AudioEffectsDisabler {

    fun disableIfAvailable(audioSessionId: Int) {
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.setEnabled(false)
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.setEnabled(false)
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.setEnabled(false)
        }
    }
}