package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot

/**
 * Runtime eligibility gate for submitting a detection's location — deliberately separate from
 * [sessionDetectionFor]'s own [LocationSnapshot.Valid] check, which only reasons about the
 * snapshot's own age/accuracy. [snapshot] alone is not enough: [DefaultLocationProvider]'s cached
 * last fix survives untouched across a runtime permission revoke or Location Services being
 * turned off mid-session, so a snapshot can still read as [LocationSnapshot.Valid] purely by age
 * even though it is no longer legitimately obtainable. [permissionLevel] and [servicesEnabled]
 * must always be read fresh at the moment of the call, never cached, so toggling either
 * immediately invalidates submission on the very next detection.
 */
internal fun currentSubmittableLocationSnapshot(
    permissionLevel: LocationPermissionLevel,
    servicesEnabled: Boolean,
    snapshot: LocationSnapshot
): LocationSnapshot? {
    if (permissionLevel == LocationPermissionLevel.NONE) {
        return null
    }
    if (!servicesEnabled) {
        return null
    }
    return snapshot
}
