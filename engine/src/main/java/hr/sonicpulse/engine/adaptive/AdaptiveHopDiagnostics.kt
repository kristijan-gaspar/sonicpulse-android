package hr.sonicpulse.engine.adaptive

/**
 * Immutable, read-only, per-hop diagnostic snapshot of the values [AdaptiveDetectionEngine]
 * already computes while processing one hop. Observational only — nothing in production
 * detection logic reads this back, and building it never recomputes or alters any
 * trigger/decision/threshold/state-machine behavior.
 *
 * While [analysisReady] is `false` (the 4096-sample analysis window is still filling), every
 * field below other than [hopIndex]/[dbfs]/[stateBefore]/[stateAfter] is `null` — genuinely
 * not yet computed for this hop, never a fabricated placeholder.
 */
data class AdaptiveHopDiagnostics(
    val hopIndex: Long,
    val analysisReady: Boolean,
    val dbfs: Double,
    val power: Double?,
    val crestDb: Double?,
    val clipRatio: Double?,
    val mfa: Double?,
    val variation: Double?,
    val th: Double?,
    val threshold: Double?,
    val isBootstrapping: Boolean?,
    val energyExceeded: Boolean?,
    val impulsive: Boolean?,
    val trigger: Boolean?,
    val stateBefore: AdaptiveDetectionState,
    val stateAfter: AdaptiveDetectionState
)
