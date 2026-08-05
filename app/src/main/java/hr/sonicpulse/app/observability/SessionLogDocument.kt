package hr.sonicpulse.app.observability

import kotlinx.serialization.Serializable

/** Schema version of the exported session-log JSON — bump only on a breaking shape change.
 * Version 2 replaced the accepted-only `detections` list with an outcome-carrying `candidates`
 * list (see [CandidateLogEntry]) and added candidate-retention accounting
 * ([SessionLogDocument.totalCandidateCount], [SessionLogDocument.recordedCandidateCount],
 * [SessionLogDocument.candidatesTruncated]). Version 3 replaced the baseline-relative
 * `releaseSpikeMin` field with the peak-relative `releaseDropDb` (see
 * [hr.sonicpulse.engine.EngineConfig.releaseDropDb]) — a testing-only artifact gated behind
 * `BuildConfig.ENABLE_SESSION_LOGGING`, so no migration path for existing exported files. */
const val SESSION_LOG_SCHEMA_VERSION = 3

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
    val sampleRate: Int,
    val blockSize: Int,
    val engineConfig: EngineConfigSnapshot,
    val candidates: List<CandidateLogEntry>,
    /** Every finalized candidate in the session, accepted or rejected alike — keeps increasing
     * past [MAX_RECORDED_CANDIDATES_PER_SESSION][JsonDetectionSessionLogger] even once
     * [candidates] itself stops growing. */
    val totalCandidateCount: Int,
    /** `candidates.size` — never exceeds `MAX_RECORDED_CANDIDATES_PER_SESSION`. */
    val recordedCandidateCount: Int,
    /** True once [totalCandidateCount] exceeds [recordedCandidateCount] — i.e. at least one
     * finalized candidate was counted but not retained in detail. */
    val candidatesTruncated: Boolean
)

@Serializable
data class DeviceInfoSnapshot(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
)

/** 1:1 field mirror of [hr.sonicpulse.engine.EngineConfig] — kept as a separate, `:app`-local
 * `@Serializable` type rather than annotating `EngineConfig` itself, so `:engine` never gains a
 * serialization dependency it doesn't otherwise need. */
@Serializable
data class EngineConfigSnapshot(
    val sampleRate: Int,
    val blockSize: Int,
    val alphaDown: Double,
    val alphaUp: Double,
    val dbfsMin: Double,
    val spikeMin: Double,
    val releaseDropDb: Double,
    val crestMin: Double,
    val crestWindowBlocks: Int,
    val clipLevel: Int,
    val clipRatioMin: Double,
    val endSilenceBlocks: Int,
    val maxEventDurationBlocks: Int,
    val cooldownBlocks: Int,
    val warmupBlocks: Int,
    val dbfsFloor: Double
)

/** One finalized [hr.sonicpulse.engine.CandidateCompletion] — accepted or rejected alike, with
 * enough context to understand why the engine decided what it did. [outcome] is `"ACCEPTED"` or
 * `"REJECTED"` and [rejectionReason] is `null` for an accepted candidate or the engine's
 * [hr.sonicpulse.engine.CandidateRejectionReason] name (currently only `"TOO_LONG"`) for a
 * rejected one — plain strings, not a mirrored `@Serializable` enum, matching how [BlockLogEntry.state]
 * already stores [hr.sonicpulse.engine.DetectionState] as a name rather than a dedicated type.
 *
 * [peakDbfs]/[peakBlockIndex]/[durationBlocks] come directly from the engine's
 * [hr.sonicpulse.engine.CandidateCompletion] — never recomputed here. [durationBlocks]/[durationMillis]
 * and [maxDbfs]/[maxSpike]/[maxCrestFactorDb]/[maxClipRatio] describe the *complete* candidate
 * (its release-active blocks plus any internal inactive gaps, and — for an accepted candidate —
 * its trailing inactive confirmation blocks), derived from every block that actually arrived —
 * regardless of [blocksTruncated]. [blocks] additionally includes the small pre-event ring-buffer
 * context for reference, so it covers a slightly wider span than the duration/max figures do, and
 * — for an accepted candidate — may also include trailing inactive confirmation blocks beyond
 * [durationBlocks]; [durationBlocks] is never inferred from [blocks].
 *
 * [totalEventBlockCount] is the real number of blocks the candidate consisted of;
 * [recordedBlockCount] is `blocks.size` (pre-event context plus however many detailed blocks were
 * actually retained). The two differ, and [blocksTruncated] is true, only for a candidate long
 * enough to exceed [JsonDetectionSessionLogger.MAX_DETAILED_EVENT_BLOCKS] — a pathological
 * sustained trigger, not a normal impulsive event. */
@Serializable
data class CandidateLogEntry(
    val outcome: String,
    val rejectionReason: String?,
    val detectedAt: String,
    val peakDbfs: Double,
    val peakBlockIndex: Long,
    val durationBlocks: Int,
    val durationMillis: Long,
    val maxDbfs: Double,
    val maxSpike: Double,
    val maxCrestFactorDb: Double?,
    val maxClipRatio: Double,
    val totalEventBlockCount: Int,
    val recordedBlockCount: Int,
    val blocksTruncated: Boolean,
    val blocks: List<BlockLogEntry>
)

/** One [hr.sonicpulse.engine.BlockMetrics] record belonging to a candidate — either part of the
 * candidate itself, or the small pre-event ring-buffer context that preceded it (both are
 * present in [CandidateLogEntry.blocks]; [relativeToPeakMillis] can be negative for pre-event or
 * early-candidate blocks). [crestFactorDb] is a peak-to-RMS ratio expressed in dB, not a level
 * relative to digital full scale — despite the sibling fields' `Dbfs` naming, crest factor is
 * never dBFS. */
@Serializable
data class BlockLogEntry(
    val blockIndex: Long,
    val relativeToPeakMillis: Long,
    val rms: Double,
    val dbfs: Double,
    val baselineDbfs: Double,
    val spikeDb: Double,
    val crestFactorDb: Double?,
    val clipRatio: Double,
    val state: String
)
