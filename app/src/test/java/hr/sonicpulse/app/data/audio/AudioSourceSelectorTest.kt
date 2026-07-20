package hr.sonicpulse.app.data.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceSelectorTest {

    @Test
    fun `selects UNPROCESSED when the device reports support for it`() {
        val source = AudioSourceSelector.select(supportsUnprocessed = true)

        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, source)
    }

    @Test
    fun `falls back to VOICE_RECOGNITION when UNPROCESSED is not supported`() {
        val source = AudioSourceSelector.select(supportsUnprocessed = false)

        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, source)
    }
}
