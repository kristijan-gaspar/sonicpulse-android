package hr.sonicpulse.app.observability

import kotlinx.serialization.Serializable

/** Schema version of the exported session-log JSON — bump only on a breaking shape change. */
const val SESSION_LOG_SCHEMA_VERSION = 1

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
    val detections: List<DetectionLogEntry>
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
    val crestMin: Double,
    val crestWindowBlocks: Int,
    val clipLevel: Int,
    val clipRatioMin: Double,
    val endSilenceBlocks: Int,
    val cooldownBlocks: Int,
    val warmupBlocks: Int,
    val dbfsFloor: Double
)

/** One accepted [hr.sonicpulse.engine.DetectionEvent], with enough context to understand why the
 * engine accepted it. [durationBlocks]/[durationMillis] and [maxDbfs]/[maxSpike]/[maxCrestFactorDb]/
 * [maxClipRatio] describe the *complete* triggered event (DETECTING plus its non-trigger tail),
 * derived from every block that actually arrived — regardless of [blocksTruncated]. [blocks]
 * additionally includes the small pre-event ring-buffer context for reference, so it covers a
 * slightly wider span than the duration/max figures do.
 *
 * [totalEventBlockCount] is the real number of blocks the event consisted of;
 * [recordedBlockCount] is `blocks.size` (pre-event context plus however many detailed event
 * blocks were actually retained). The two differ, and [blocksTruncated] is true, only for an
 * event long enough to exceed [JsonDetectionSessionLogger.MAX_DETAILED_EVENT_BLOCKS] — a
 * pathological sustained trigger, not a normal impulsive event. */
@Serializable
data class DetectionLogEntry(
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

/** One [hr.sonicpulse.engine.BlockMetrics] record belonging to a detection — either part of the
 * triggered event itself, or the small pre-event ring-buffer context that preceded it (both are
 * present in [DetectionLogEntry.blocks]; [relativeToPeakMillis] can be negative for pre-event or
 * early-event blocks). [crestFactorDb] is a peak-to-RMS ratio expressed in dB, not a level
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
