package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent

/**
 * Owns only the V2 event lifecycle/state (`IDLE -> DETECTING -> COOLDOWN -> IDLE`) — no
 * DSP, no Dufaux/background internals. [process] receives already-computed per-hop
 * values from [AdaptiveDetectionEngine] and decides state transitions and peak tracking
 * from them alone.
 */
class AdaptiveDetectionStateMachine(private val config: AdaptiveEngineConfig) {

    var state: AdaptiveDetectionState = AdaptiveDetectionState.IDLE
        private set

    private var consecutiveInactiveHops = 0
    private var cooldownHopCount = 0
    private var eventStartBlockIndex = 0L
    private var lastActiveBlockIndex = 0L
    private var eventPeakDbfs = Double.NEGATIVE_INFINITY
    private var eventPeakBlockIndex = 0L

    /** The adaptive threshold frozen at the moment the current event's `DETECTING` began. */
    private var frozenEventThreshold = 0.0

    /** [frozenEventThreshold] while an event is active, for diagnostics — mirrors
     * [AdaptiveDetectionEngine.lastDbfs]'s always-available-after-the-fact shape. `null`
     * outside `DETECTING`. */
    val activeEventThreshold: Double?
        get() = if (state == AdaptiveDetectionState.DETECTING) frozenEventThreshold else null

    /** How the most recently finished candidate (this [process] call, if any) concluded —
     * diagnostic-only, see [AdaptiveCandidateCompletion]. `null` on every hop that did not
     * just finish a candidate, cleared at the start of every [process] call. */
    var lastCandidateCompletion: AdaptiveCandidateCompletion? = null
        private set

    /**
     * Processes one hop's already-computed values and returns the [DetectionEvent] that
     * just closed on this hop, or `null` on every other hop.
     *
     * [trigger] and [adaptiveThreshold] are only consulted while in [AdaptiveDetectionState.IDLE]
     * (trigger opens a new event and freezes [adaptiveThreshold] as this event's threshold);
     * [currentPower] is only consulted while [AdaptiveDetectionState.DETECTING] (compared
     * against the frozen threshold to decide whether the event is still active).
     */
    fun process(
        trigger: Boolean,
        currentPower: Double,
        currentDbfs: Double,
        blockIndex: Long,
        adaptiveThreshold: Double
    ): DetectionEvent? {
        require(currentPower.isFinite() && currentPower >= 0.0) {
            "currentPower must be finite and non-negative, was $currentPower."
        }
        require(currentDbfs.isFinite()) { "currentDbfs must be finite, was $currentDbfs." }
        require(blockIndex >= 0) { "blockIndex must be non-negative, was $blockIndex." }
        require(adaptiveThreshold.isFinite()) {
            "adaptiveThreshold must be finite, was $adaptiveThreshold."
        }

        lastCandidateCompletion = null

        return when (state) {
            AdaptiveDetectionState.IDLE -> handleIdle(trigger, currentDbfs, blockIndex, adaptiveThreshold)
            AdaptiveDetectionState.DETECTING -> handleDetecting(currentPower, currentDbfs, blockIndex)
            AdaptiveDetectionState.COOLDOWN -> handleCooldown()
        }
    }

    fun reset() {
        state = AdaptiveDetectionState.IDLE
        consecutiveInactiveHops = 0
        cooldownHopCount = 0
        lastCandidateCompletion = null
        resetEventTracking()
    }

    private fun handleIdle(
        trigger: Boolean,
        dbfs: Double,
        blockIndex: Long,
        adaptiveThreshold: Double
    ): DetectionEvent? {
        if (trigger) {
            state = AdaptiveDetectionState.DETECTING
            frozenEventThreshold = adaptiveThreshold
            eventStartBlockIndex = blockIndex
            lastActiveBlockIndex = blockIndex
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
            consecutiveInactiveHops = 0
        }
        return null
    }

    private fun handleDetecting(currentPower: Double, dbfs: Double, blockIndex: Long): DetectionEvent? {
        val stillActive = currentPower > frozenEventThreshold
        return if (stillActive) handleActiveHop(dbfs, blockIndex) else handleInactiveHop()
    }

    private fun handleActiveHop(dbfs: Double, blockIndex: Long): DetectionEvent? {
        lastActiveBlockIndex = blockIndex
        consecutiveInactiveHops = 0
        if (dbfs > eventPeakDbfs) {
            eventPeakDbfs = dbfs
            eventPeakBlockIndex = blockIndex
        }

        // Span from onset to the latest active hop, inclusive.
        val durationHopsLong = blockIndex - eventStartBlockIndex + 1L
        if (durationHopsLong <= config.maxEventDurationHops.toLong()) {
            return null
        }

        // Too long: no DetectionEvent (production behavior unchanged) — but record the
        // diagnostic completion so this rejection is observable outside this class.
        val durationHops = durationHopsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        lastCandidateCompletion = AdaptiveCandidateCompletion.Rejected(
            reason = AdaptiveCandidateRejectionReason.TOO_LONG,
            peakDbfs = eventPeakDbfs,
            peakBlockIndex = eventPeakBlockIndex,
            durationHops = durationHops
        )
        enterCooldown()
        resetEventTracking()
        return null
    }

    private fun handleInactiveHop(): DetectionEvent? {
        consecutiveInactiveHops++
        if (consecutiveInactiveHops < config.endSilenceHops) {
            return null
        }

        // Up to the last hop that was actually still active.
        val durationHopsLong = lastActiveBlockIndex - eventStartBlockIndex + 1L
        val durationHops = durationHopsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val event = DetectionEvent(
            peakDbfs = eventPeakDbfs,
            peakBlockIndex = eventPeakBlockIndex,
            durationBlocks = durationHops
        )
        lastCandidateCompletion = AdaptiveCandidateCompletion.Accepted(event)
        enterCooldown()
        resetEventTracking()
        return event
    }

    private fun handleCooldown(): DetectionEvent? {
        cooldownHopCount++
        if (cooldownHopCount >= config.cooldownHops) {
            state = AdaptiveDetectionState.IDLE
        }
        return null
    }

    private fun enterCooldown() {
        state = AdaptiveDetectionState.COOLDOWN
        cooldownHopCount = 0
        consecutiveInactiveHops = 0
    }

    private fun resetEventTracking() {
        eventStartBlockIndex = 0L
        lastActiveBlockIndex = 0L
        eventPeakDbfs = Double.NEGATIVE_INFINITY
        eventPeakBlockIndex = 0L
        frozenEventThreshold = 0.0
    }
}
