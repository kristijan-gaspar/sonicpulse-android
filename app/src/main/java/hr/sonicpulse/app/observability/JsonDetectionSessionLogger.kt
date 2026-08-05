package hr.sonicpulse.app.observability

import android.os.Build
import hr.sonicpulse.engine.BlockMetrics
import hr.sonicpulse.engine.CandidateCompletion
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
 * Candidate-centric [DetectionSessionLogger]: rather than recording every block for an entire
 * session, it keeps only a small ring buffer of recent [BlockMetrics] while no candidate is in
 * progress, and only actually retains data once the engine enters `DETECTING`. It records every
 * finalized [CandidateCompletion] — accepted or rejected alike — not only accepted ones.
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

        /** Upper bound on the number of [BlockMetrics] retained in detail for a single candidate.
         * 100 blocks ≈ 2.3s at the default 1024-sample/44.1kHz block duration (~23.2ms/block) —
         * comfortably longer than any genuine impulsive event, which by default ends within
         * [EngineConfig.endSilenceBlocks] (3 blocks, ~70ms) of the trigger stopping. The cap
         * exists for the pathological case: a sustained loud signal (continuous blowing into the
         * mic, prolonged clipping) can keep the engine in `DETECTING` indefinitely, and without a
         * bound [PendingCandidate.detailBlocks] would grow for as long as that lasts — until
         * [EngineConfig.maxEventDurationBlocks] forces a `TOO_LONG` rejection. Once the cap is
         * reached, aggregate figures (max dbfs/spike/crest/clipRatio, total block count, duration)
         * keep updating from every block that actually arrives — only the *detailed* per-block
         * records stop being appended. */
        const val MAX_DETAILED_EVENT_BLOCKS = 100

        /** Upper bound on the number of [CandidateLogEntry] objects retained for a single session
         * — see [ActiveSession.canRetainAnotherCandidate]. Every finalized candidate is still
         * counted past this limit (`SessionLogDocument.totalCandidateCount`, via
         * [ActiveSession.countDiscardedCandidate]); only the detailed [CandidateLogEntry] objects
         * themselves stop being built, the same truncation strategy [MAX_DETAILED_EVENT_BLOCKS]
         * applies within a single candidate — and for the same reason: [onBlock] only ever calls
         * [PendingCandidate.finalize] (which combines pre-event/detail blocks, maps every
         * [BlockMetrics] to a [BlockLogEntry], and allocates the resulting [CandidateLogEntry])
         * while [ActiveSession.canRetainAnotherCandidate] still holds, so a session past the cap
         * never pays that cost on the audio capture thread only to immediately discard the result.
         * Exists for the same pathological reason as [MAX_DETAILED_EVENT_BLOCKS]: a noisy
         * environment could otherwise generate an unbounded number of rejected candidates over a
         * long session. */
        const val MAX_RECORDED_CANDIDATES_PER_SESSION = 500
    }

    /** One in-progress candidate: pre-event context copied once at DETECTING onset, plus every
     * block belonging to the candidate itself (its release-active blocks, any internal inactive
     * gaps, and — for an eventually accepted candidate — its trailing inactive confirmation tail)
     * as they arrive — detailed per-block records capped at [MAX_DETAILED_EVENT_BLOCKS];
     * aggregates and counts keep accumulating from every block regardless of the cap. */
    private class PendingCandidate(private val preEventContext: List<BlockMetrics>) {
        private val detailBlocks = mutableListOf<BlockMetrics>()
        private var totalBlockCount = 0
        private var maxDbfs = Double.NEGATIVE_INFINITY
        private var maxSpike = Double.NEGATIVE_INFINITY
        private var maxCrestFactorDb: Double? = null
        private var maxClipRatio = 0.0

        fun addBlock(metrics: BlockMetrics) {
            totalBlockCount++
            if (detailBlocks.size < MAX_DETAILED_EVENT_BLOCKS) {
                detailBlocks += metrics
            }
            if (metrics.dbfs > maxDbfs) maxDbfs = metrics.dbfs
            if (metrics.spike > maxSpike) maxSpike = metrics.spike
            metrics.crest?.let { crest ->
                if (maxCrestFactorDb == null || crest > maxCrestFactorDb!!) maxCrestFactorDb = crest
            }
            if (metrics.clipRatio > maxClipRatio) maxClipRatio = metrics.clipRatio
        }

        /** [completion] supplies the authoritative [peakDbfs]/[peakBlockIndex]/[durationBlocks] —
         * never recomputed from [detailBlocks] or [totalBlockCount], since the trace may (for an
         * accepted candidate) include trailing inactive confirmation blocks beyond the engine's
         * own duration span. */
        fun finalize(completion: CandidateCompletion, peakTimeClient: Instant, sampleRate: Int, blockSize: Int): CandidateLogEntry {
            val blockDurationMillis = blockSize * 1000.0 / sampleRate
            val outcome: String
            val rejectionReason: String?
            val peakDbfs: Double
            val peakBlockIndex: Long
            val durationBlocks: Int
            when (completion) {
                is CandidateCompletion.Accepted -> {
                    outcome = "ACCEPTED"
                    rejectionReason = null
                    peakDbfs = completion.event.peakDbfs
                    peakBlockIndex = completion.event.peakBlockIndex
                    durationBlocks = completion.event.durationBlocks
                }
                is CandidateCompletion.Rejected -> {
                    outcome = "REJECTED"
                    rejectionReason = completion.reason.name
                    peakDbfs = completion.peakDbfs
                    peakBlockIndex = completion.peakBlockIndex
                    durationBlocks = completion.durationBlocks
                }
            }
            val allBlocks = preEventContext + detailBlocks
            return CandidateLogEntry(
                outcome = outcome,
                rejectionReason = rejectionReason,
                detectedAt = peakTimeClient.toString(),
                peakDbfs = peakDbfs,
                peakBlockIndex = peakBlockIndex,
                durationBlocks = durationBlocks,
                durationMillis = (durationBlocks * blockDurationMillis).toLong(),
                maxDbfs = maxDbfs,
                maxSpike = maxSpike,
                maxCrestFactorDb = maxCrestFactorDb,
                maxClipRatio = maxClipRatio,
                totalEventBlockCount = totalBlockCount,
                recordedBlockCount = allBlocks.size,
                blocksTruncated = totalBlockCount > detailBlocks.size,
                blocks = allBlocks.map { metrics ->
                    BlockLogEntry(
                        blockIndex = metrics.blockIndex,
                        relativeToPeakMillis = ((metrics.blockIndex - peakBlockIndex) * blockDurationMillis).toLong(),
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
     * [onBlock] call for it, not [startSession]), accumulating candidates and ring-buffer/pending-
     * candidate state as further blocks arrive. */
    private class ActiveSession(val sessionId: String, val startedAt: Instant, val config: EngineConfig, val device: DeviceInfoSnapshot) {
        val ringBuffer = ArrayDeque<BlockMetrics>(PRE_EVENT_CONTEXT_BLOCKS)
        val candidates = mutableListOf<CandidateLogEntry>()
        var totalCandidateCount = 0
            private set
        var pendingCandidate: PendingCandidate? = null

        fun pushToRingBuffer(metrics: BlockMetrics) {
            ringBuffer.addLast(metrics)
            if (ringBuffer.size > PRE_EVENT_CONTEXT_BLOCKS) {
                ringBuffer.removeFirst()
            }
        }

        /** True while another [CandidateLogEntry] can still be retained — callers must check this
         * *before* calling [PendingCandidate.finalize], never after, so the expensive conversion
         * (block-list merge, [BlockLogEntry] mapping, [CandidateLogEntry] allocation) is only ever
         * performed for a candidate that will actually be kept. */
        val canRetainAnotherCandidate: Boolean
            get() = candidates.size < MAX_RECORDED_CANDIDATES_PER_SESSION

        /** Retains [entry] and counts it — only ever called while [canRetainAnotherCandidate]
         * held at the time [entry] was built. */
        fun recordCandidate(entry: CandidateLogEntry) {
            totalCandidateCount++
            candidates += entry
        }

        /** Counts a finalized candidate that was never turned into a [CandidateLogEntry] at all —
         * see [SessionLogDocument.candidatesTruncated]. */
        fun countDiscardedCandidate() {
            totalCandidateCount++
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

    override fun onBlock(metrics: BlockMetrics, finalizedCandidate: FinalizedCandidate?) {
        synchronized(lock) {
            val session = activateIfNeeded() ?: return
            val pending = session.pendingCandidate
            when {
                pending != null -> pending.addBlock(metrics)
                metrics.state == DetectionState.DETECTING -> {
                    val started = PendingCandidate(preEventContext = session.ringBuffer.toList())
                    // Cleared, not just copied: without this, a second candidate starting before
                    // the ring buffer refills (e.g. short/zero cooldown) would inherit pre-event
                    // blocks that actually belong to before the *previous* candidate.
                    session.ringBuffer.clear()
                    started.addBlock(metrics)
                    session.pendingCandidate = started
                }
                else -> session.pushToRingBuffer(metrics)
            }

            // Finalizes on any completion — accepted or rejected alike — never only when the
            // engine's own process() call returned a non-null DetectionEvent.
            if (finalizedCandidate != null) {
                val finishedPending = session.pendingCandidate
                if (finishedPending != null) {
                    // canRetainAnotherCandidate is checked *before* finalize() runs: past the
                    // retention cap, finalize()'s block-merge/mapping/allocation work would only
                    // ever be discarded, so it must never run on the audio thread for a candidate
                    // that won't be kept — only the count is still incremented.
                    if (session.canRetainAnotherCandidate) {
                        session.recordCandidate(
                            finishedPending.finalize(
                                finalizedCandidate.completion,
                                finalizedCandidate.peakTimeClient,
                                session.config.sampleRate,
                                session.config.blockSize
                            )
                        )
                    } else {
                        session.countDiscardedCandidate()
                    }
                    session.pendingCandidate = null
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
                candidates = session.candidates.toList(),
                totalCandidateCount = session.totalCandidateCount,
                recordedCandidateCount = session.candidates.size,
                candidatesTruncated = session.totalCandidateCount > session.candidates.size
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
    releaseDropDb = releaseDropDb,
    crestMin = crestMin,
    crestWindowBlocks = crestWindowBlocks,
    clipLevel = clipLevel,
    clipRatioMin = clipRatioMin,
    endSilenceBlocks = endSilenceBlocks,
    maxEventDurationBlocks = maxEventDurationBlocks,
    cooldownBlocks = cooldownBlocks,
    warmupBlocks = warmupBlocks,
    dbfsFloor = dbfsFloor
)
