package hr.sonicpulse.app.data.audio

/**
 * Pure decision logic for a single `AudioRecord.read()` result inside [AudioRecorder]'s capture
 * loop — Android-free so the exact ordering the loop depends on (a requested Stop always wins,
 * even over a read that returned a genuine sample count or error code) is directly testable with
 * plain JVM unit tests, without Robolectric or mocking `AudioRecord` itself.
 */
sealed interface AudioReadDecision {

    /** `stop()` was requested — the read result, whatever it is, must never reach the engine. */
    data object StopRequested : AudioReadDecision

    /** No stop was requested and the read genuinely failed. */
    data class ReadError(val samplesRead: Int) : AudioReadDecision

    /** No stop was requested and the read succeeded — safe to hand to [AudioBlockAccumulator]. */
    data object Deliver : AudioReadDecision

    companion object {
        fun decide(stopRequested: Boolean, samplesRead: Int): AudioReadDecision = when {
            stopRequested -> StopRequested
            samplesRead < 0 -> ReadError(samplesRead)
            else -> Deliver
        }
    }
}
