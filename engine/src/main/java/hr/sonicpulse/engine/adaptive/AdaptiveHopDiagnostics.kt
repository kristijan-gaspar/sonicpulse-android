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
 *
 * [crestDb] is the crest factor of the current 1024-sample hop alone — the value the
 * detection trigger actually uses (`crestDb > crestMinDb`). [crestWindowDb] is the crest
 * factor of the whole current 4096-sample rolling analysis window — diagnostic only, not
 * used by any trigger/threshold logic, kept so 1024-vs-4096 crest can be compared on real
 * recordings.
 */
data class AdaptiveHopDiagnostics(
    val hopIndex: Long,
    val analysisReady: Boolean,
    val dbfs: Double,
    val power: Double?,
    val crestDb: Double?,
    val crestWindowDb: Double?,
    val clipRatio: Double?,
    val mfa: Double?,
    val variation: Double?,
    val th: Double?,
    val threshold: Double?,
    val isBootstrapping: Boolean?,
    val energyExceeded: Boolean?,
    val relativePowerExceeded: Boolean?,
    val impulsive: Boolean?,
    val trigger: Boolean?,
    val stateBefore: AdaptiveDetectionState,
    val stateAfter: AdaptiveDetectionState
)
