package hr.sonicpulse.app.data.audio

import android.media.MediaRecorder

object AudioSourceSelector {

    fun select(supportsUnprocessed: Boolean): Int =
        if (supportsUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
}