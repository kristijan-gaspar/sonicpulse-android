package hr.sonicpulse.engine.adaptive

/**
 * Immutable, per-hop diagnostic snapshot of everything that went into the V2 detector's
 * decision for one hop. Testing-only — nothing in production detection logic reads this;
 * it exists purely so a session logger can reconstruct/explain detector behavior (see
 * `hr.sonicpulse.app.observability`), including false-negative hops where [trigger] never
 * fired.
 *
 * Every field genuinely computable for a hop is populated, even one where [analysisReady]
 * is `false` (the 4096-sample analysis window is still filling): [hopIndex], [dbfs],
 * [crestDb] and [clipRatio] only need the current hop's own samples. Fields that
 * [analysisReady] being `false` makes genuinely undefined — [power] and everything
 * downstream of it — are `null`, never a fabricated placeholder.
 */
data class AdaptiveHopDiagnostics(
    val hopIndex: Long,
    /** True once the 4096-sample analysis window is full and a power/trigger decision was
     * possible for this hop. Every field below other than [dbfs]/[crestDb]/[clipRatio] is
     * `null` while this is `false`. */
    val analysisReady: Boolean,

    val dbfs: Double,
    val power: Double?,
    /** `null` for pure silence (undefined crest factor), independent of [analysisReady]. */
    val crestDb: Double?,
    val clipRatio: Double?,

    /** Number of background observations admitted so far — grows toward
     * [AdaptiveEngineConfig.backgroundHistoryCapacity] regardless of [analysisReady]. */
    val backgroundSampleCount: Int,
    val mfa: Double?,
    /** Classic background standard deviation — see [RobustThresholdEvaluation.stdPower]. */
    val stdPower: Double?,
    val cmfa: Double?,
    val tha: Double?,
    val variation: Double?,
    val th: Double?,
    val threshold: Double?,
    val isBootstrapping: Boolean?,

    val energyExceeded: Boolean?,
    val crestExceeded: Boolean?,
    val clipExceeded: Boolean?,
    val impulsive: Boolean?,
    val trigger: Boolean?,

    val stateBefore: AdaptiveDetectionState,
    val stateAfter: AdaptiveDetectionState,
    /** The frozen threshold an already-active event's `currentPower > frozenEventThreshold`
     * check used for this hop — `null` unless [stateBefore] was `DETECTING`. */
    val activeEventThreshold: Double?,
    /** Set exactly on the hop a candidate finished on — accepted or rejected alike. `null`
     * on every other hop. */
    val candidateCompletion: AdaptiveCandidateCompletion?
)
