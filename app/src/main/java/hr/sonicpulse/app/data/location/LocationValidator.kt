package hr.sonicpulse.app.data.location

/**
 * Pure classification of a raw fix against LocationPolicy — deterministic precedence:
 * NoFixYet > Invalid > Stale > Inaccurate > Valid. A fix that is both stale and inaccurate is
 * reported as Stale (staleness is checked first). Permission denial is not represented here —
 * it's a monitoring startup/runtime failure (MonitoringStartupFailure), not a per-detection
 * location classification.
 */
object LocationValidator {

    fun evaluate(
        fix: RawLocationFix?,
        policy: LocationPolicy,
        nowElapsedRealtimeNanos: Long
    ): LocationSnapshot {
        if (fix == null) {
            return LocationSnapshot.NoFixYet
        }

        val ageNanos = nowElapsedRealtimeNanos - fix.elapsedRealtimeNanos
        if (ageNanos < 0L) {
            return LocationSnapshot.Invalid
        }

        val maxAgeNanos = policy.maxLocationAgeMillis * 1_000_000L
        if (ageNanos > maxAgeNanos) {
            return LocationSnapshot.Stale(ageNanos / 1_000_000L)
        }
        if (fix.accuracyMeters > policy.maxLocationAccuracyMeters) {
            return LocationSnapshot.Inaccurate(fix.accuracyMeters)
        }
        return LocationSnapshot.Valid(fix.latitude, fix.longitude, fix.accuracyMeters)
    }
}
