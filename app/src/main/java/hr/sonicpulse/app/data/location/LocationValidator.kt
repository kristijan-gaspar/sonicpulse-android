package hr.sonicpulse.app.data.location

/**
 * Pure classification of a raw fix against LocationPolicy — deterministic precedence:
 * PermissionDenied > NoFixYet > Stale > Inaccurate > Valid. A fix that is both stale and
 * inaccurate is reported as Stale (staleness is checked first).
 */
object LocationValidator {

    fun evaluate(
        fix: RawLocationFix?,
        permissionLevel: LocationPermissionLevel,
        policy: LocationPolicy,
        nowElapsedRealtimeNanos: Long
    ): LocationSnapshot {
        if (permissionLevel == LocationPermissionLevel.NONE) {
            return LocationSnapshot.PermissionDenied
        }
        if (fix == null) {
            return LocationSnapshot.NoFixYet
        }

        val ageMillis = (nowElapsedRealtimeNanos - fix.elapsedRealtimeNanos) / 1_000_000L
        if (ageMillis > policy.maxLocationAgeMillis) {
            return LocationSnapshot.Stale(ageMillis)
        }
        if (fix.accuracyMeters > policy.maxLocationAccuracyMeters) {
            return LocationSnapshot.Inaccurate(fix.accuracyMeters)
        }
        return LocationSnapshot.Valid(fix.latitude, fix.longitude, fix.accuracyMeters)
    }
}
