package hr.sonicpulse.app.observability

import kotlinx.serialization.Serializable

/** Schema version of the exported session-log JSON — bump only on a breaking shape change.
 * Version 2 replaced the accepted-only `detections` list with an outcome-carrying `candidates`
 * list and added candidate-retention accounting. Version 3 replaced the baseline-relative
 * `releaseSpikeMin` field with the peak-relative `releaseDropDb`. Version 4 replaced the whole
 * V1 candidate-centric, `EngineConfig`-based document with the V2 adaptive-engine document
 * below: a full per-hop [HopLogEntry] trace (so a hop where the detector never triggered is
 * still visible — needed to diagnose false negatives, which a candidate-only log cannot show)
 * and an [AdaptiveEngineConfigSnapshot] mirroring [hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig].
 * A testing-only artifact gated behind `BuildConfig.ENABLE_SESSION_LOGGING`, so no migration
 * path for existing exported files. */
const val SESSION_LOG_SCHEMA_VERSION = 4

/** One completed monitoring session, ready to serialize and export — the top-level exported
 * document. All instants are ISO-8601 strings (`java.time.Instant.toString()`), not epoch
 * millis, so the file is directly human-readable. */
@Serializable
data class SessionLogDocument(
    val schemaVersion: Int,
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val device: DeviceInfoSnapshot,
    val engineConfig: AdaptiveEngineConfigSnapshot,
    val hops: List<HopLogEntry>,
    /** Every hop actually processed in the session — keeps increasing past
     * [JsonDetectionSessionLogger]'s retention limit even once [hops] itself stops growing. */
    val totalHopCount: Int,
    /** `hops.size` — never exceeds the logger's retention limit. */
    val recordedHopCount: Int,
    /** True once [totalHopCount] exceeds [recordedHopCount] — i.e. at least one processed hop
     * was counted but not retained in detail. Never silent: always computable from the two
     * counts above, even without this flag. */
    val hopsTruncated: Boolean
)

@Serializable
data class DeviceInfoSnapshot(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
)

/** 1:1 field mirror of [hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig] — kept as a
 * separate, `:app`-local `@Serializable` type rather than annotating the engine config
 * itself, so `:engine` never gains a serialization dependency it doesn't otherwise need. */
@Serializable
data class AdaptiveEngineConfigSnapshot(
    val sampleRate: Int,
    val hopSize: Int,
    val analysisWindowSize: Int,
    val backgroundHistoryMillis: Int,
    val backgroundHistoryCapacity: Int,
    val thresholdStdMultiplier: Double,
    val variationHistoryMillis: Int,
    val variationHistoryCapacity: Int,
    val ov: Double,
    val crestMinDb: Double,
    val clipLevel: Int,
    val clipRatioMin: Double,
    val endSilenceHops: Int,
    val maxEventDurationMillis: Int,
    val maxEventDurationHops: Int,
    val cooldownMillis: Int,
    val cooldownHops: Int
)

/** One finalized candidate completion — accepted or rejected alike, embedded on exactly the
 * [HopLogEntry] it finished on. [outcome] is `"ACCEPTED"` or `"REJECTED"` and
 * [rejectionReason] is `null` for an accepted candidate or the engine's
 * [hr.sonicpulse.engine.adaptive.AdaptiveCandidateRejectionReason] name (currently only
 * `"TOO_LONG"`) for a rejected one — a plain string, not a mirrored `@Serializable` enum,
 * matching how [HopLogEntry.stateBefore]/[HopLogEntry.stateAfter] already store
 * [hr.sonicpulse.engine.adaptive.AdaptiveDetectionState] as a name rather than a dedicated
 * type. [peakDbfs]/[peakBlockIndex]/[durationHops] come directly from the engine's
 * [hr.sonicpulse.engine.adaptive.AdaptiveCandidateCompletion] — never recomputed here. */
@Serializable
data class HopCompletionLogEntry(
    val outcome: String,
    val rejectionReason: String?,
    val peakDbfs: Double,
    val peakBlockIndex: Long,
    val durationHops: Int
)

/** One [hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics] record. Fields the detector
 * genuinely could not compute yet (the 4096-sample analysis window still filling, or
 * background history not yet ready) are `null`, matching [analysisReady] — never a
 * fabricated placeholder. [crestDb] is a peak-to-RMS ratio expressed in dB, not a level
 * relative to digital full scale — despite the sibling fields' `Dbfs`/`db`-adjacent naming,
 * crest factor is never dBFS. [completion] is non-null exactly on the hop a candidate
 * finished on, accepted or rejected alike. */
@Serializable
data class HopLogEntry(
    val hopIndex: Long,
    val analysisReady: Boolean,
    val dbfs: Double,
    val power: Double?,
    val crestDb: Double?,
    val clipRatio: Double?,
    val backgroundSampleCount: Int,
    val mfa: Double?,
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
    val stateBefore: String,
    val stateAfter: String,
    val activeEventThreshold: Double?,
    val completion: HopCompletionLogEntry?
)
