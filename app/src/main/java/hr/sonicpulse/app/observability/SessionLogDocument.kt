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
 * engine accepted it. [durationBlocks]/[durationMillis] and [maxDbfs]/[maxSpike]/[maxCrest]/
 * [maxClipRatio] describe only the triggered event itself (DETECTING plus its non-trigger tail);
 * [blocks] (and [blockCount]) additionally include the small pre-event ring-buffer context for
 * reference, so they cover a slightly wider span than the duration/max figures do. */
@Serializable
data class DetectionLogEntry(
    val detectedAt: String,
    val peakDbfs: Double,
    val peakBlockIndex: Long,
    val durationBlocks: Int,
    val durationMillis: Long,
    val maxDbfs: Double,
    val maxSpike: Double,
    val maxCrest: Double?,
    val maxClipRatio: Double,
    val blockCount: Int,
    val blocks: List<BlockLogEntry>
)

/** One [hr.sonicpulse.engine.BlockMetrics] record belonging to a detection — either part of the
 * triggered event itself, or the small pre-event ring-buffer context that preceded it (both are
 * present in [DetectionLogEntry.blocks]; [relativeToPeakMillis] can be negative for pre-event or
 * early-event blocks). */
@Serializable
data class BlockLogEntry(
    val blockIndex: Long,
    val relativeToPeakMillis: Long,
    val rms: Double,
    val dbfs: Double,
    val baselineDbfs: Double,
    val spikeDb: Double,
    val crestDbfs: Double?,
    val clipRatio: Double,
    val state: String
)
