package hr.sonicpulse.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioReadDecisionTest {

    @Test
    fun `stopRequested true and a positive read result in StopRequested, never Deliver`() {
        val decision = AudioReadDecision.decide(stopRequested = true, samplesRead = 128)

        assertEquals(AudioReadDecision.StopRequested, decision)
    }

    @Test
    fun `stopRequested true and a negative read result in StopRequested, never a reported read error`() {
        val decision = AudioReadDecision.decide(stopRequested = true, samplesRead = -3)

        assertEquals(AudioReadDecision.StopRequested, decision)
    }

    @Test
    fun `stopRequested false and a positive read result in Deliver`() {
        val decision = AudioReadDecision.decide(stopRequested = false, samplesRead = 128)

        assertEquals(AudioReadDecision.Deliver, decision)
    }

    @Test
    fun `stopRequested false and a negative read result in ReadError carrying the error code`() {
        val decision = AudioReadDecision.decide(stopRequested = false, samplesRead = -3)

        assertEquals(AudioReadDecision.ReadError(-3), decision)
    }
}
