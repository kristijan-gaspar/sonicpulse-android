package hr.sonicpulse.app.data.location

import com.google.android.gms.location.Priority

data class LocationPolicy(
    val maxLocationAgeMillis: Long = 10_000,
    val maxLocationAccuracyMeters: Float = 50.0f,
    /** Below [maxLocationAgeMillis] with a deliberate margin (currently 2 s, 20% of the 10 s
     * budget): a fix delivered on this cadence is always well inside the freshness window at
     * [LocationValidator]'s next evaluation, not sitting right at its edge where ordinary FLP
     * delivery jitter could tip an otherwise-good fix into [LocationSnapshot.Stale]. Previously
     * 5_000 (a 2x margin) — requesting a fresh GPS fix twice as often as the freshness policy
     * ever requires was pure battery cost with no correctness benefit; this keeps the same
     * priority/accuracy bar and only removes that redundant polling. */
    val updateIntervalMillis: Long = 8_000,
    val priority: Int = Priority.PRIORITY_HIGH_ACCURACY
) {
    init {
        require(maxLocationAgeMillis > 0) {
            "maxLocationAgeMillis must be positive, was $maxLocationAgeMillis."
        }
        require(maxLocationAccuracyMeters.isFinite() && maxLocationAccuracyMeters > 0f) {
            "maxLocationAccuracyMeters must be finite and positive, was $maxLocationAccuracyMeters."
        }
        require(updateIntervalMillis > 0) {
            "updateIntervalMillis must be positive, was $updateIntervalMillis."
        }
    }
}
