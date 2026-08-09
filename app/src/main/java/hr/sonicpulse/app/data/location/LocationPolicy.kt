package hr.sonicpulse.app.data.location

import com.google.android.gms.location.Priority

data class LocationPolicy(
    val maxLocationAgeMillis: Long = 10_000,
    val maxLocationAccuracyMeters: Float = 50.0f,
    /** Configured with a nominal 2 s margin below [maxLocationAgeMillis] (8 s request interval
     * against a 10 s freshness budget): the *intent* is that a fix requested on this cadence
     * lands comfortably inside the freshness window at [LocationValidator]'s next evaluation, not
     * right at its edge. This is a target, not a guarantee — `LocationRequest`'s interval is a
     * desired cadence for Fused Location, not a delivery SLA; actual updates can arrive slower
     * (weak signal, Doze, provider batching) or occasionally faster, so this margin does not by
     * itself prove every fix stays fresh at runtime. Previously 5_000 (a nominal 2x margin).
     * Lowering the requested frequency is intended to reduce GPS request pressure and battery
     * cost versus that previous value, but the actual on-device freshness/stale rate — and any
     * real battery effect — has not been measured and must be validated on a physical device
     * before assuming a specific reduction. Priority/accuracy bar are unchanged. */
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
