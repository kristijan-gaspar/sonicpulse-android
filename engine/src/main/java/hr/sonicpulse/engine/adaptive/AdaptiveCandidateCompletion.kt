package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent

/**
 * Diagnostic-only record of how a V2 candidate event finished — accepted or rejected
 * alike. Production behavior is unaffected by this type: an accepted candidate already
 * returns its [DetectionEvent] from [AdaptiveDetectionStateMachine.process] unchanged, and
 * a rejected (`TOO_LONG`) candidate still produces no [DetectionEvent] and therefore no
 * submission. This only exists so diagnostics/logging can see *that* and *why* a candidate
 * was rejected — information [process]'s return value alone cannot show, since a rejection
 * is otherwise silent outside [AdaptiveDetectionStateMachine].
 */
sealed interface AdaptiveCandidateCompletion {

    data class Accepted(val event: DetectionEvent) : AdaptiveCandidateCompletion

    data class Rejected(
        val reason: AdaptiveCandidateRejectionReason,
        val peakDbfs: Double,
        val peakBlockIndex: Long,
        val durationHops: Int
    ) : AdaptiveCandidateCompletion
}
