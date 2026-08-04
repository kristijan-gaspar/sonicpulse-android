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
 * it only ever appends to a small, bounded, in-memory structure, never touches JSON or I/O.
 * [startSession]/[finishSession] run on the service's main thread. Because those are genuinely
 * different threads, every method that touches mutable state synchronizes on [lock] — cheap
 * (a handful of field reads/list appends under an uncontended lock), and the actual guarantee
 * against a torn-in-progress read comes from *sequencing*: [hr.sonicpulse.app.service.MonitoringService]
 * only calls [finishSession] after the capture thread has already been fully drained, so by the
 * time it runs, no further [onBlock] call can still be in flight.
 */
@Singleton
class JsonDetectionSessionLogger @Inject constructor() : DetectionSessionLogger {

    private companion object {
        /** Small and documented, per the brief: ~5 blocks (≈116ms at the standard 1024/44100
         * block duration) of context immediately before a trigger — enough to see what the
         * baseline/level looked like just before the event, without retaining unbounded history
         * or, per the brief, any raw PCM samples. */
        const val PRE_EVENT_CONTEXT_BLOCKS = 5
    }

    /** One in-progress detection: pre-event context copied once at DETECTING onset, plus every
     * block belonging to the event itself (DETECTING and its non-trigger tail) as they arrive. */
    private class PendingEvent(private val preEventContext: List<BlockMetrics>) {
        private val eventBlocks = mutableListOf<BlockMetrics>()
        private var maxDbfs = Double.NEGATIVE_INFINITY
        private var maxSpike = Double.NEGATIVE_INFINITY
        private var maxCrest: Double? = null
        private var maxClipRatio = 0.0

        fun addEventBlock(metrics: BlockMetrics) {
            eventBlocks += metrics
            if (metrics.dbfs > maxDbfs) maxDbfs = metrics.dbfs
            if (metrics.spike > maxSpike) maxSpike = metrics.spike
            metrics.crest?.let { crest -> if (maxCrest == null || crest > maxCrest!!) maxCrest = crest }
            if (metrics.clipRatio > maxClipRatio) maxClipRatio = metrics.clipRatio
        }

        fun finalize(event: DetectionEvent, peakTimeClient: Instant, sampleRate: Int, blockSize: Int): DetectionLogEntry {
            val blockDurationMillis = blockSize * 1000.0 / sampleRate
            val firstEventBlockIndex = eventBlocks.first().blockIndex
            val lastEventBlockIndex = eventBlocks.last().blockIndex
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
                maxCrest = maxCrest,
                maxClipRatio = maxClipRatio,
                blockCount = allBlocks.size,
                blocks = allBlocks.map { metrics ->
                    BlockLogEntry(
                        blockIndex = metrics.blockIndex,
                        relativeToPeakMillis = ((metrics.blockIndex - event.peakBlockIndex) * blockDurationMillis).toLong(),
                        rms = metrics.rms,
                        dbfs = metrics.dbfs,
                        baselineDbfs = metrics.baseline,
                        spikeDb = metrics.spike,
                        crestDbfs = metrics.crest,
                        clipRatio = metrics.clipRatio,
                        state = metrics.state.name
                    )
                }
            )
        }
    }

    /** One in-progress session: identity/config captured at [startSession], accumulating
     * detections and ring-buffer/pending-event state as blocks arrive. */
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
            activeSession = ActiveSession(
                sessionId = UUID.randomUUID().toString(),
                startedAt = Instant.now(),
                config = config,
                device = DeviceInfoSnapshot(manufacturer, model, sdkInt)
            )
            completedDocument = null
            _hasCompletedSession.value = false
        }
    }

    override fun onBlock(metrics: BlockMetrics, event: FinalizedEvent?) {
        synchronized(lock) {
            val session = activeSession ?: return
            val pending = session.pendingEvent
            when {
                pending != null -> pending.addEventBlock(metrics)
                metrics.state == DetectionState.DETECTING -> {
                    val started = PendingEvent(preEventContext = session.ringBuffer.toList())
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

    override fun finishSession() {
        synchronized(lock) {
            val session = activeSession ?: return
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

    override fun exportJson(): String? = synchronized(lock) {
        completedDocument?.let { json.encodeToString(SessionLogDocument.serializer(), it) }
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
