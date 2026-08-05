package hr.sonicpulse.app.observability

import android.os.Build
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.DetectionState
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Event-centric [DetectionSessionLogger]: rather than recording every block for an entire
 * session, it keeps only a small ring buffer of recent [BlockMetrics] while no event is in
 * progress, and only actually retains data once the engine enters `DETECTING`.
 *
 * Threading: [onBlock] runs on the audio capture thread (per
 * [hr.sonicpulse.app.service.MonitoringService]'s own threading contract) and must stay cheap —
 * it only ever appends to small, bounded, in-memory structures, never touches JSON or I/O.
 * [startSession]/[finishSession] run on the service's main thread. Because those are genuinely
 * different threads, every method that touches mutable state synchronizes on [lock] — cheap
 * (a handful of field reads/list appends under an uncontended lock), and the actual guarantee
 * against a torn-in-progress read comes from *sequencing*: [hr.sonicpulse.app.service.MonitoringService]
 * only calls [finishSession] after the capture thread has already been fully drained, so by the
 * time it runs, no further [onBlock] call can still be in flight.
 *
 * Session lifecycle is split into two states, deliberately, so a capture attempt that never
 * produces audio (permission failure, `AudioRecord` creation failure, `startRecording()`
 * failure) cannot destroy the previous, genuinely completed session:
 *  - [startSession] only *prepares* a session (captures config/device identity).
 *  - [onBlock]'s first call for that prepared session *activates* it — see [activateIfNeeded] —
 *    which is the point [ActiveSession.startedAt] is captured and the previous
 *    [completedDocument] is finally discarded.
 */
@Singleton
class JsonDetectionSessionLogger @Inject constructor() : DetectionSessionLogger {

    private companion object {
        /** Small and documented, per the brief: ~5 blocks (≈116ms at the standard 1024/44100
         * block duration) of context immediately before a trigger — enough to see what the
         * baseline/level looked like just before the event, without retaining unbounded history
         * or, per the brief, any raw PCM samples. */
        const val PRE_EVENT_CONTEXT_BLOCKS = 5

        /** Upper bound on the number of [BlockMetrics] retained in detail for a single event.
         * 100 blocks ≈ 2.3s at the default 1024-sample/44.1kHz block duration (~23.2ms/block) —
         * comfortably longer than any genuine impulsive event, which by default ends within
         * [EngineConfig.endSilenceBlocks] (3 blocks, ~70ms) of the trigger stopping. The cap
         * exists for the pathological case: a sustained loud signal (continuous blowing into the
         * mic, prolonged clipping) can keep the engine in `DETECTING` indefinitely, and without a
         * bound [PendingEvent.eventBlocks] would grow for as long as that lasts. Once the cap is
         * reached, aggregate figures (max dbfs/spike/crest/clipRatio, total block count, event
         * duration) keep updating from every block that actually arrives — only the *detailed*
         * per-block records stop being appended. */
        const val MAX_DETAILED_EVENT_BLOCKS = 100
    }

    /** One in-progress detection: pre-event context copied once at DETECTING onset, plus every
     * block belonging to the event itself (DETECTING and its non-trigger tail) as they arrive —
     * detailed per-block records capped at [MAX_DETAILED_EVENT_BLOCKS]; aggregates and counts
     * keep accumulating from every block regardless of the cap. */
    private class PendingEvent(private val preEventContext: List<BlockMetrics>) {
        private val eventBlocks = mutableListOf<BlockMetrics>()
        private var totalEventBlockCount = 0
        private var firstEventBlockIndex = 0L
        private var lastEventBlockIndex = 0L
        private var maxDbfs = Double.NEGATIVE_INFINITY
        private var maxSpike = Double.NEGATIVE_INFINITY
        private var maxCrestFactorDb: Double? = null
        private var maxClipRatio = 0.0

        fun addEventBlock(metrics: BlockMetrics) {
            if (totalEventBlockCount == 0) firstEventBlockIndex = metrics.blockIndex
            lastEventBlockIndex = metrics.blockIndex
            totalEventBlockCount++
            if (eventBlocks.size < MAX_DETAILED_EVENT_BLOCKS) {
                eventBlocks += metrics
            }
            if (metrics.dbfs > maxDbfs) maxDbfs = metrics.dbfs
            if (metrics.spike > maxSpike) maxSpike = metrics.spike
            metrics.crest?.let { crest ->
                if (maxCrestFactorDb == null || crest > maxCrestFactorDb!!) maxCrestFactorDb = crest
            }
            if (metrics.clipRatio > maxClipRatio) maxClipRatio = metrics.clipRatio
        }

        fun finalize(event: DetectionEvent, peakTimeClient: Instant, sampleRate: Int, blockSize: Int): DetectionLogEntry {
            val blockDurationMillis = blockSize * 1000.0 / sampleRate
            // Full event span, from the real first/last block index — not from eventBlocks.size,
            // which may be capped while the underlying event was longer.
            val durationBlocks = (lastEventBlockIndex - firstEventBlockIndex + 1).toInt()
            val allBlocks = preEventContext + eventBlocks
            return DetectionLogEntry(
                detectedAt = peakTimeClient.toString(),
                peakDbfs = event.peakDbfs,
                peakBlockIndex = event.peakBlockIndex,
                durationBlocks = durationBlocks,
                durationMillis = (durationBlocks * blockDurationMillis).toLong(),
                maxDbfs = maxDbfs,
                maxSpike = maxSpike,
                maxCrestFactorDb = maxCrestFactorDb,
                maxClipRatio = maxClipRatio,
                totalEventBlockCount = totalEventBlockCount,
                recordedBlockCount = allBlocks.size,
                blocksTruncated = totalEventBlockCount > eventBlocks.size,
                blocks = allBlocks.map { metrics ->
                    BlockLogEntry(
                        blockIndex = metrics.blockIndex,
                        relativeToPeakMillis = ((metrics.blockIndex - event.peakBlockIndex) * blockDurationMillis).toLong(),
                        rms = metrics.rms,
                        dbfs = metrics.dbfs,
                        baselineDbfs = metrics.baseline,
                        spikeDb = metrics.spike,
                        crestFactorDb = metrics.crest,
                        clipRatio = metrics.clipRatio,
                        state = metrics.state.name
                    )
                }
            )
        }
    }

    /** A session that [startSession] has captured config/device identity for, but that has not
     * yet received a single real block — see [activateIfNeeded]. */
    private class PreparedSession(val config: EngineConfig, val device: DeviceInfoSnapshot)

    /** One genuinely in-progress session: identity/[startedAt] captured at activation (the first
     * [onBlock] call for it, not [startSession]), accumulating detections and ring-buffer/pending-
     * event state as further blocks arrive. */
    private class ActiveSession(val sessionId: String, val startedAt: Instant, val config: EngineConfig, val device: DeviceInfoSnapshot) {
        val ringBuffer = ArrayDeque<BlockMetrics>(PRE_EVENT_CONTEXT_BLOCKS)
        val detections = mutableListOf<DetectionLogEntry>()
        var pendingEvent: PendingEvent? = null

        fun pushToRingBuffer(metrics: BlockMetrics) {
            ringBuffer.addLast(metrics)
            if (ringBuffer.size > PRE_EVENT_CONTEXT_BLOCKS) {
                ringBuffer.removeFirst()
            }
        }
    }

    private val lock = Any()
    private var preparedSession: PreparedSession? = null
    private var activeSession: ActiveSession? = null
    private var completedDocument: SessionLogDocument? = null

    private val json = Json { prettyPrint = true }

    private val _hasCompletedSession = MutableStateFlow(false)
    override val hasCompletedSession: StateFlow<Boolean> = _hasCompletedSession.asStateFlow()

    override fun startSession(config: EngineConfig) {
        startSession(config, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)
    }

    /** [manufacturer]/[model]/[sdkInt] are parameters (defaulting to the real [Build] fields in
     * production, see the single-argument overload above) purely so tests never need to touch
     * `android.os.Build` at all. */
    fun startSession(config: EngineConfig, manufacturer: String, model: String, sdkInt: Int) {
        synchronized(lock) {
            preparedSession = PreparedSession(config, DeviceInfoSnapshot(manufacturer, model, sdkInt))
            // Discards a still-in-progress (already activated) session's buffered data — but,
            // deliberately, leaves completedDocument/hasCompletedSession untouched: the previous
            // export must survive a capture attempt that never activates (see activateIfNeeded).
            activeSession = null
        }
    }

    override fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?) {
        synchronized(lock) {
            val session = activateIfNeeded() ?: return
            val pending = session.pendingEvent
            when {
                pending != null -> pending.addEventBlock(metrics)
                metrics.state == DetectionState.DETECTING -> {
                    val started = PendingEvent(preEventContext = session.ringBuffer.toList())
                    // Cleared, not just copied: without this, a second event starting before the
                    // ring buffer refills (e.g. short/zero cooldown) would inherit pre-event
                    // blocks that actually belong to before the *previous* event.
                    session.ringBuffer.clear()
                    started.addEventBlock(metrics)
                    session.pendingEvent = started
                }
                else -> session.pushToRingBuffer(metrics)
            }

            if (event != null) {
                val finished = session.pendingEvent
                if (finished != null) {
                    session.detections += finished.finalize(
                        event.event,
                        event.peakTimeClient,
                        session.config.sampleRate,
                        session.config.blockSize
                    )
                    session.pendingEvent = null
                }
            }
        }
    }

    /** Must be called with [lock] already held. Promotes [preparedSession] to [activeSession] on
     * the first block that genuinely arrives for it — this, not [startSession], is the moment a
     * capture attempt is considered to have "really started": [ActiveSession.startedAt] is
     * captured here, and only here is the previous [completedDocument] finally discarded. A
     * capture attempt that fails before any block arrives therefore never disturbs the last
     * export (see [finishSession]). */
    private fun activateIfNeeded(): ActiveSession? {
        activeSession?.let { return it }
        val prepared = preparedSession ?: return null
        val activated = ActiveSession(
            sessionId = UUID.randomUUID().toString(),
            startedAt = Instant.now(),
            config = prepared.config,
            device = prepared.device
        )
        activeSession = activated
        preparedSession = null
        completedDocument = null
        _hasCompletedSession.value = false
        return activated
    }

    override fun finishSession() {
        synchronized(lock) {
            val session = activeSession
            if (session == null) {
                // Never activated (no block arrived before a failure/stop) — discard the prepared
                // attempt, but leave any previous completedDocument exactly as it was.
                preparedSession = null
                return
            }
            activeSession = null
            completedDocument = SessionLogDocument(
                schemaVersion = SESSION_LOG_SCHEMA_VERSION,
                sessionId = session.sessionId,
                startedAt = session.startedAt.toString(),
                endedAt = Instant.now().toString(),
                device = session.device,
                sampleRate = session.config.sampleRate,
                blockSize = session.config.blockSize,
                engineConfig = session.config.toSnapshot(),
                detections = session.detections.toList()
            )
            _hasCompletedSession.value = true
        }
    }

    override fun exportJson(): String? {
        // Snapshot under the lock, serialize outside it: completedDocument is an immutable data
        // class assigned wholesale (never mutated in place) once built, so releasing the lock
        // before the (potentially expensive) pretty-print pass cannot expose a torn read — and it
        // stops JSON serialization from ever blocking a concurrent onBlock() on the capture thread.
        val snapshot = synchronized(lock) { completedDocument } ?: return null
        return json.encodeToString(SessionLogDocument.serializer(), snapshot)
    }
}

private fun EngineConfig.toSnapshot(): EngineConfigSnapshot = EngineConfigSnapshot(
    sampleRate = sampleRate,
    blockSize = blockSize,
    alphaDown = alphaDown,
    alphaUp = alphaUp,
    dbfsMin = dbfsMin,
    spikeMin = spikeMin,
    crestMin = crestMin,
    crestWindowBlocks = crestWindowBlocks,
    clipLevel = clipLevel,
    clipRatioMin = clipRatioMin,
    endSilenceBlocks = endSilenceBlocks,
    cooldownBlocks = cooldownBlocks,
    warmupBlocks = warmupBlocks,
    dbfsFloor = dbfsFloor
)
