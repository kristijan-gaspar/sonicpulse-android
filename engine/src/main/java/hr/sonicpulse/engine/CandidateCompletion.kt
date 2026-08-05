package hr.sonicpulse.engine

/**
 * The outcome of a candidate acoustic event as it leaves `DETECTING`, exposed via
 * [DetectionEngine.lastCandidateCompletion] regardless of whether [DetectionEngine.process]
 * itself returns a [DetectionEvent] — [Rejected] candidates never do.
 */
sealed interface CandidateCompletion {

    /** A candidate that closed normally within [EngineConfig.maxEventDurationBlocks] — the same
     * [event] value [DetectionEngine.process] returned on this block. */
    data class Accepted(val event: DetectionEvent) : CandidateCompletion

    /** A candidate discarded before it could close normally — [DetectionEngine.process] returns
     * null on the block this is set. [peakDbfs]/[peakBlockIndex]/[durationBlocks] describe the
     * candidate as it stood at the moment of rejection, including the block that triggered it. */
    data class Rejected(
        val reason: CandidateRejectionReason,
        val peakDbfs: Double,
        val peakBlockIndex: Long,
        val durationBlocks: Int
    ) : CandidateCompletion
}
