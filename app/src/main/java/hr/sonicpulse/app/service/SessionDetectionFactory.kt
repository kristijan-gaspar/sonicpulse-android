package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.audio.PeakTimeCalculator
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.observability.FinalizedCandidate
import hr.sonicpulse.engine.CandidateCompletion
import java.time.Instant
import java.util.UUID

/**
 * Builds a [SessionDetection] for an impulse event only when [locationSnapshot] — read once, at
 * the exact moment of handoff — is currently [LocationSnapshot.Valid]. NoFixYet/Stale/Inaccurate/
 * Invalid mean the impulse is simply not reported: no detection, no submission, no counters —
 * while the engine and monitoring session continue normally. A previously Valid snapshot carries
 * no freshness metadata of its own, so this must always be evaluated against the exact snapshot
 * passed in here, never a cached earlier one — bypassing that would bypass the location-age policy.
 */
internal fun sessionDetectionFor(
    peakDbfs: Double,
    peakTimeClient: Instant,
    locationSnapshot: LocationSnapshot
): SessionDetection? {
    if (locationSnapshot !is LocationSnapshot.Valid) {
        return null
    }
    return SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = peakDbfs,
        peakTimeClient = peakTimeClient,
        location = locationSnapshot
    )
}

/** Derives the diagnostic-log peak instant from [completion]'s own peak block index — via
 * [PeakTimeCalculator], never `Instant.now()` — so an accepted candidate's logged peak time
 * always matches the same-block [PeakTimeCalculator] call that produces the submitted
 * [SessionDetection.peakTimeClient], and a rejected candidate (which never produces a
 * [hr.sonicpulse.engine.DetectionEvent] to derive one from) still gets a correct peak instant for
 * the diagnostic log entry. Pure and Android-free, like [sessionDetectionFor] above, so it is
 * directly unit-testable without a [hr.sonicpulse.app.service.MonitoringService] instance. */
internal fun finalizedCandidateFor(
    completion: CandidateCompletion,
    startInstant: Instant,
    sampleRate: Int,
    blockSize: Int
): FinalizedCandidate {
    val peakBlockIndex = when (completion) {
        is CandidateCompletion.Accepted -> completion.event.peakBlockIndex
        is CandidateCompletion.Rejected -> completion.peakBlockIndex
    }
    val peakTimeClient = PeakTimeCalculator.calculate(startInstant, peakBlockIndex, sampleRate, blockSize)
    return FinalizedCandidate(completion, peakTimeClient)
}
