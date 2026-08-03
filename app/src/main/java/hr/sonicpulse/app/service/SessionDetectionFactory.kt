package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.SessionDetection
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
