package hr.sonicpulse.app.observability

import android.os.Build
import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import hr.sonicpulse.engine.adaptive.AdaptiveHopDiagnostics
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Per-hop [DetectionSessionLogger]: retains a bounded, in-order trace of every
 * [AdaptiveHopDiagnostics] the V2 engine produces for a session — not only accepted
 * candidates — so a hop where the detector never triggered (a potential false negative) is
 * still visible in the export.
 *
 * [config] is the shared, `:app`-wide [AdaptiveEngineConfig] singleton (see `di/DetectionModule`)
 * — the same instance [hr.sonicpulse.app.detection.AdaptiveDetectionProcessorFactory] builds
 * every session's processor from, so the exported [AdaptiveEngineConfigSnapshot] always
 * describes the config that actually ran.
 *
 * Threading: [onHop] runs on the audio capture thread (per
 * [hr.sonicpulse.app.service.MonitoringService]'s own threading contract) and must stay cheap —
 * it only ever appends to a small, bounded, in-memory list, never touches JSON or I/O.
 * [startSession]/[finishSession] run on the service's main thread. Because those are genuinely
 * different threads, every method that touches mutable state synchronizes on [lock] — cheap
 * (a field read/list append under an uncontended lock), and the actual guarantee against a
 * torn-in-progress read comes from *sequencing*: [hr.sonicpulse.app.service.MonitoringService]
 * only calls [finishSession] after the capture thread has already been fully drained, so by the
 * time it runs, no further [onHop] call can still be in flight.
 *
 * Session lifecycle is split into two states, deliberately, so a capture attempt that never
 * produces audio (permission failure, `AudioRecord` creation failure, `startRecording()`
 * failure) cannot destroy the previous, genuinely completed session:
 *  - [startSession] only *prepares* a session (captures device identity).
 *  - [onHop]'s first call for that prepared session *activates* it — see [activateIfNeeded] —
 *    which is the point [ActiveSession.startedAt] is captured and the previous
 *    [completedDocument] is finally discarded.
 */
@Singleton
class JsonDetectionSessionLogger @Inject constructor(
    private val config: AdaptiveEngineConfig
) : DetectionSessionLogger {

    private companion object {
        /** Upper bound on the number of per-hop diagnostics records retained in detail for a
         * single session. 5,000 hops is roughly 1.9 minutes at the default 1024/44100 hop
         * duration (~23.2ms/hop) — a manual, controlled test/validation session
         * (this is a testing-only feature, not a bound on production monitoring duration).
         * Every hop past this limit still counts toward [ActiveSession.totalHopCount]; only
         * the detailed [HopLogEntry] stops being retained — never a silent truncation, since
         * [SessionLogDocument.hopsTruncated] always reflects it. */
        const val MAX_RECORDED_HOPS_PER_SESSION = 5_000
    }

    /** A session that [startSession] has captured device identity for, but that has not yet
     * received a single real hop — see [activateIfNeeded]. */
    private class PreparedSession(val device: DeviceInfoSnapshot)

    /** One genuinely in-progress session: identity/[startedAt] captured at activation (the first
     * [onHop] call for it, not [startSession]), accumulating hops as they arrive. */
    private class ActiveSession(val sessionId: String, val startedAt: Instant, val device: DeviceInfoSnapshot) {
        val hops = mutableListOf<HopLogEntry>()
        var totalHopCount = 0
            private set

        fun recordHop(entry: HopLogEntry) {
            totalHopCount++
            if (hops.size < MAX_RECORDED_HOPS_PER_SESSION) {
                hops += entry
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

    override fun startSession() {
        startSession(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)
    }

    /** [manufacturer]/[model]/[sdkInt] are parameters (defaulting to the real [Build] fields in
     * production, see the single-argument overload above) purely so tests never need to touch
     * `android.os.Build` at all. */
    fun startSession(manufacturer: String, model: String, sdkInt: Int) {
        synchronized(lock) {
            preparedSession = PreparedSession(DeviceInfoSnapshot(manufacturer, model, sdkInt))
            // Discards a still-in-progress (already activated) session's buffered data — but,
            // deliberately, leaves completedDocument/hasCompletedSession untouched: the previous
            // export must survive a capture attempt that never activates (see activateIfNeeded).
            activeSession = null
        }
    }

    override fun onHop(diagnostics: AdaptiveHopDiagnostics, event: DetectionEvent?) {
        synchronized(lock) {
            val session = activateIfNeeded() ?: return
            session.recordHop(diagnostics.toLogEntry(event))
        }
    }

    /** Must be called with [lock] already held. Promotes [preparedSession] to [activeSession] on
     * the first hop that genuinely arrives for it — this, not [startSession], is the moment a
     * capture attempt is considered to have "really started": [ActiveSession.startedAt] is
     * captured here, and only here is the previous [completedDocument] finally discarded. A
     * capture attempt that fails before any hop arrives therefore never disturbs the last
     * export (see [finishSession]). */
    private fun activateIfNeeded(): ActiveSession? {
        activeSession?.let { return it }
        val prepared = preparedSession ?: return null
        val activated = ActiveSession(
            sessionId = UUID.randomUUID().toString(),
            startedAt = Instant.now(),
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
                // Never activated (no hop arrived before a failure/stop) — discard the prepared
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
                adaptiveEngineConfig = config.toSnapshot(),
                hops = session.hops.toList(),
                totalHopCount = session.totalHopCount,
                recordedHopCount = session.hops.size,
                hopsTruncated = session.totalHopCount > session.hops.size
            )
            _hasCompletedSession.value = true
        }
    }

    override fun exportJson(): String? {
        // Snapshot under the lock, serialize outside it: completedDocument is an immutable data
        // class assigned wholesale (never mutated in place) once built, so releasing the lock
        // before the (potentially expensive) pretty-print pass cannot expose a torn read — and it
        // stops JSON serialization from ever blocking a concurrent onHop() on the capture thread.
        val snapshot = synchronized(lock) { completedDocument } ?: return null
        return json.encodeToString(SessionLogDocument.serializer(), snapshot)
    }
}

private fun AdaptiveEngineConfig.toSnapshot(): AdaptiveEngineConfigSnapshot = AdaptiveEngineConfigSnapshot(
    sampleRate = sampleRate,
    hopSize = hopSize,
    analysisWindowSize = analysisWindowSize,
    backgroundHistoryMillis = backgroundHistoryMillis,
    thresholdStdMultiplier = thresholdStdMultiplier,
    variationHistoryMillis = variationHistoryMillis,
    ov = ov,
    crestMinDb = crestMinDb,
    clipLevel = clipLevel,
    clipRatioMin = clipRatioMin,
    endSilenceHops = endSilenceHops,
    maxEventDurationMillis = maxEventDurationMillis,
    cooldownMillis = cooldownMillis
)

private fun DetectionEvent.toLogEntry(): DetectionEventLogEntry = DetectionEventLogEntry(
    peakDbfs = peakDbfs,
    peakBlockIndex = peakBlockIndex,
    durationBlocks = durationBlocks
)

private fun AdaptiveHopDiagnostics.toLogEntry(event: DetectionEvent?): HopLogEntry = HopLogEntry(
    hopIndex = hopIndex,
    analysisReady = analysisReady,
    dbfs = dbfs,
    power = power,
    crestDb = crestDb,
    clipRatio = clipRatio,
    mfa = mfa,
    variation = variation,
    th = th,
    threshold = threshold,
    isBootstrapping = isBootstrapping,
    energyExceeded = energyExceeded,
    impulsive = impulsive,
    trigger = trigger,
    stateBefore = stateBefore.name,
    stateAfter = stateAfter.name,
    event = event?.toLogEntry()
)
