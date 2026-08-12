package hr.sonicpulse.app.observability

import kotlinx.serialization.Serializable

/** Schema version of the exported session-log JSON — bump only on a breaking shape change.
 * Version 2 replaced the accepted-only `detections` list with an outcome-carrying `candidates`
 * list and added candidate-retention accounting. Version 3 replaced the baseline-relative
 * `releaseSpikeMin` field with the peak-relative `releaseDropDb`. Version 4 replaced the whole
 * V1 candidate-centric, `EngineConfig`-based document with the V2 per-hop document below: a
 * full [HopLogEntry] trace (so a hop where the detector never triggered is still visible —
 * needed to diagnose false negatives, which a candidate-only log cannot show, since a missed
 * impulse may never create a candidate at all) and an [AdaptiveEngineConfigSnapshot] mirroring
 * [hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig]. A testing-only artifact gated behind
 * `BuildConfig.ENABLE_SESSION_LOGGING`, so no migration path for existing exported files. */
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
    val adaptiveEngineConfig: AdaptiveEngineConfigSnapshot,
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

/** The configuration inputs of [hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig] actually
 * used to build this session's processor — kept as a separate, `:app`-local `@Serializable`
 * type rather than annotating the engine config itself, so `:engine` never gains a
 * serialization dependency it doesn't otherwise need. Only the configured inputs are mirrored
 * here, not the capacities/hop counts derived from them (`backgroundHistoryCapacity`,
 * `variationHistoryCapacity`, `maxEventDurationHops`, `cooldownHops`) — those are always
 * reproducible from the inputs above. */
@Serializable
data class AdaptiveEngineConfigSnapshot(
    val sampleRate: Int,
    val hopSize: Int,
    val analysisWindowSize: Int,
    val backgroundHistoryMillis: Int,
    val thresholdStdMultiplier: Double,
    val variationHistoryMillis: Int,
    val ov: Double,
    val crestMinDb: Double,
    val clipLevel: Int,
    val clipRatioMin: Double,
    val endSilenceHops: Int,
    val maxEventDurationMillis: Int,
    val cooldownMillis: Int
)

/** The [hr.sonicpulse.engine.DetectionEvent] that closed on a given hop, copied verbatim —
 * never recomputed here. */
@Serializable
data class DetectionEventLogEntry(
    val peakDbfs: Double,
    val peakBlockIndex: Long,
    val durationBlocks: Int
)

/** One [hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics] record. Fields the detector
 * genuinely could not compute yet (the 4096-sample analysis window still filling) are `null`,
 * matching [analysisReady] — never a fabricated placeholder. [crestDb] is a peak-to-RMS ratio
 * expressed in dB, not a level relative to digital full scale — despite the sibling fields'
 * `Dbfs`/`db`-adjacent naming, crest factor is never dBFS. [stateBefore]/[stateAfter] mirror
 * [hr.sonicpulse.engine.adaptive.AdaptiveDetectionState] as plain name strings, not a mirrored
 * `@Serializable` enum, so `:engine` never gains a serialization dependency. [event] is
 * non-null exactly on the hop a candidate was accepted on. */
@Serializable
data class HopLogEntry(
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
    val stateBefore: String,
    val stateAfter: String,
    val event: DetectionEventLogEntry?
)
