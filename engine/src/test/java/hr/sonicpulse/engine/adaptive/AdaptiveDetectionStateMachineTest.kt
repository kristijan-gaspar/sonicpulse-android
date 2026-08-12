package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDetectionStateMachineTest {

    // hopSize=100 samples @ sampleRate=1000Hz -> 100ms/hop.
    // maxEventDurationMillis=300 -> 3 hops. cooldownMillis=200 -> 2 hops. endSilenceHops=3.
    private val config = AdaptiveEngineConfig(
        sampleRate = 1000,
        hopSize = 100,
        analysisWindowSize = 400,
        maxEventDurationMillis = 300,
        cooldownMillis = 200,
        endSilenceHops = 3
    )

    @Test
    fun `derived test hop counts are maxEventDurationHops=3, cooldownHops=2`() {
        assertEquals(3, config.maxEventDurationHops)
        assertEquals(2, config.cooldownHops)
    }

    @Test
    fun `no trigger in IDLE leaves state at IDLE and returns no event`() {
        val sm = AdaptiveDetectionStateMachine(config)

        val event = sm.process(
            trigger = false,
            currentPower = 1.0,
            currentDbfs = -10.0,
            blockIndex = 0,
            adaptiveThreshold = 0.5
        )

        assertNull(event)
        assertEquals(AdaptiveDetectionState.IDLE, sm.state)
    }

    @Test
    fun `trigger in IDLE opens DETECTING and freezes the adaptive threshold`() {
        val sm = AdaptiveDetectionStateMachine(config)

        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 0, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.DETECTING, sm.state)

        // Next hop supplies a very different "live" adaptiveThreshold (100.0). If the
        // event used that live value instead of the frozen one (2.0), currentPower=3.0
        // would NOT be "active" (3.0 <= 100.0) and the event would end immediately. It
        // must instead still be active against the frozen threshold of 2.0.
        val event = sm.process(
            trigger = false,
            currentPower = 3.0,
            currentDbfs = -4.0,
            blockIndex = 1,
            adaptiveThreshold = 100.0
        )

        assertNull(event)
        assertEquals(AdaptiveDetectionState.DETECTING, sm.state)
    }

    @Test
    fun `trigger is ignored while already DETECTING`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 0, adaptiveThreshold = 2.0)

        // trigger=true here must have no special effect; only currentPower vs the frozen
        // threshold (2.0) decides activity. currentPower=1.0 is inactive.
        sm.process(trigger = true, currentPower = 1.0, currentDbfs = -8.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = true, currentPower = 1.0, currentDbfs = -8.0, blockIndex = 2, adaptiveThreshold = 2.0)
        val event = sm.process(
            trigger = true,
            currentPower = 1.0,
            currentDbfs = -8.0,
            blockIndex = 3,
            adaptiveThreshold = 2.0
        )

        assertNotNull(event) // 3 inactive hops accepted the event normally.
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)
    }

    @Test
    fun `peak tracking follows the highest dbfs and its block index while active`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -3.0, blockIndex = 1, adaptiveThreshold = 2.0) // new peak
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -7.0, blockIndex = 2, adaptiveThreshold = 2.0) // not a new peak
        // 3 consecutive inactive hops to accept.
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 4, adaptiveThreshold = 2.0)
        val event = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 5,
            adaptiveThreshold = 2.0
        )!!

        assertEquals(-3.0, event.peakDbfs, 0.0)
        assertEquals(1L, event.peakBlockIndex)
    }

    @Test
    fun `event is accepted exactly after endSilenceHops consecutive inactive hops`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)

        // endSilenceHops=3: 2 inactive hops must not close it yet.
        val afterFirstInactive = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 1,
            adaptiveThreshold = 2.0
        )
        val afterSecondInactive = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 2,
            adaptiveThreshold = 2.0
        )
        assertNull(afterFirstInactive)
        assertNull(afterSecondInactive)
        assertEquals(AdaptiveDetectionState.DETECTING, sm.state)

        val afterThirdInactive = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 3,
            adaptiveThreshold = 2.0
        )

        assertNotNull(afterThirdInactive)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)
    }

    @Test
    fun `an active hop resets the consecutive-inactive counter`() {
        // A larger duration budget than the shared config's 3 hops, since this scenario's
        // span (onset..last active, inclusive of the inactive gap) needs to reach 4.
        val roomyConfig = config.copy(maxEventDurationMillis = 1000)
        val sm = AdaptiveDetectionStateMachine(roomyConfig)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        // 2 inactive hops, then an active one - must NOT be 1 hop away from acceptance.
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 3, adaptiveThreshold = 2.0)

        // Only 1 inactive hop again after the reset - must still be DETECTING.
        val event = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 4,
            adaptiveThreshold = 2.0
        )

        assertNull(event)
        assertEquals(AdaptiveDetectionState.DETECTING, sm.state)
    }

    @Test
    fun `event exceeding maxEventDurationHops is rejected silently and enters COOLDOWN`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        // Active hops at blockIndex 1,2 -> duration 2,3 (still <= maxEventDurationHops=3).
        val stillDetecting1 =
            sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 1, adaptiveThreshold = 2.0)
        val stillDetecting2 =
            sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 2, adaptiveThreshold = 2.0)
        assertNull(stillDetecting1)
        assertNull(stillDetecting2)
        assertEquals(AdaptiveDetectionState.DETECTING, sm.state)

        // blockIndex 3 -> duration 4 > 3: rejected, no DetectionEvent, straight to COOLDOWN.
        val rejected =
            sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 3, adaptiveThreshold = 2.0)

        assertNull(rejected)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)
    }

    @Test
    fun `cooldown returns to IDLE after exactly cooldownHops hops`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)

        // cooldownHops=2.
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 4, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)

        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 5, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.IDLE, sm.state)
    }

    @Test
    fun `trigger is ignored while in COOLDOWN`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)

        // trigger=true during COOLDOWN must not open a new event or affect hop counting.
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 4, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.COOLDOWN, sm.state)

        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 5, adaptiveThreshold = 2.0)
        assertEquals(AdaptiveDetectionState.IDLE, sm.state)
    }

    @Test
    fun `accepted DetectionEvent has correct peakDbfs, peakBlockIndex and durationBlocks`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 1, adaptiveThreshold = 2.0) // peak
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 2.0)
        val event = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 4,
            adaptiveThreshold = 2.0
        )!!

        assertEquals(DetectionEvent(peakDbfs = -5.0, peakBlockIndex = 1L, durationBlocks = 2), event)
    }

    @Test
    fun `reset returns to IDLE and clears all event and cooldown state`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -1.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -1.0, blockIndex = 1, adaptiveThreshold = 2.0)

        sm.reset()

        assertEquals(AdaptiveDetectionState.IDLE, sm.state)

        // A fresh trigger afterward must behave exactly like a brand-new state machine -
        // no leaked peak/threshold/duration state from before reset.
        sm.process(trigger = true, currentPower = 9.0, currentDbfs = -20.0, blockIndex = 100, adaptiveThreshold = 8.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 101, adaptiveThreshold = 8.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 102, adaptiveThreshold = 8.0)
        val event = sm.process(
            trigger = false,
            currentPower = 0.0,
            currentDbfs = -50.0,
            blockIndex = 103,
            adaptiveThreshold = 8.0
        )!!

        assertEquals(DetectionEvent(peakDbfs = -20.0, peakBlockIndex = 100L, durationBlocks = 1), event)
    }

    @Test
    fun `activeEventThreshold is null outside DETECTING and equals the frozen threshold during it`() {
        val sm = AdaptiveDetectionStateMachine(config)
        assertNull(sm.activeEventThreshold)

        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        assertEquals(2.0, sm.activeEventThreshold!!, 0.0)

        // A different live adaptiveThreshold on a later hop must not change the frozen value.
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 1, adaptiveThreshold = 99.0)
        assertEquals(2.0, sm.activeEventThreshold!!, 0.0)

        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 99.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 99.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 4, adaptiveThreshold = 99.0)
        assertNull(sm.activeEventThreshold) // COOLDOWN now - no active event
    }

    @Test
    fun `lastCandidateCompletion is null on ordinary hops and cleared each call`() {
        val sm = AdaptiveDetectionStateMachine(config)

        sm.process(trigger = false, currentPower = 1.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 0.5)
        assertNull(sm.lastCandidateCompletion)

        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 1, adaptiveThreshold = 2.0)
        assertNull(sm.lastCandidateCompletion) // opening a candidate is not a completion
    }

    @Test
    fun `lastCandidateCompletion is Accepted on exactly the hop that accepts the event`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -5.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 2, adaptiveThreshold = 2.0)

        val event = sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 3, adaptiveThreshold = 2.0)!!

        val completion = sm.lastCandidateCompletion
        assertTrue(completion is AdaptiveCandidateCompletion.Accepted)
        assertEquals(event, (completion as AdaptiveCandidateCompletion.Accepted).event)

        // The very next hop (nothing completes) must read null again, not a stale value.
        sm.process(trigger = false, currentPower = 0.0, currentDbfs = -50.0, blockIndex = 4, adaptiveThreshold = 2.0)
        assertNull(sm.lastCandidateCompletion)
    }

    @Test
    fun `lastCandidateCompletion is Rejected TOO_LONG on the hop that rejects it, and no DetectionEvent is returned`() {
        val sm = AdaptiveDetectionStateMachine(config)
        sm.process(trigger = true, currentPower = 5.0, currentDbfs = -10.0, blockIndex = 0, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 1, adaptiveThreshold = 2.0)
        sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 2, adaptiveThreshold = 2.0)

        // blockIndex 3 -> duration 4 > maxEventDurationHops(3): rejected.
        val result = sm.process(trigger = false, currentPower = 5.0, currentDbfs = -6.0, blockIndex = 3, adaptiveThreshold = 2.0)

        assertNull(result) // production behavior unchanged: no DetectionEvent
        val completion = sm.lastCandidateCompletion
        assertTrue(completion is AdaptiveCandidateCompletion.Rejected)
        val rejected = completion as AdaptiveCandidateCompletion.Rejected
        assertEquals(AdaptiveCandidateRejectionReason.TOO_LONG, rejected.reason)
        assertEquals(4, rejected.durationHops)
    }

    @Test
    fun `rejects a negative or non-finite currentPower`() {
        val sm = AdaptiveDetectionStateMachine(config)

        assertThrows(IllegalArgumentException::class.java) {
            sm.process(trigger = false, currentPower = -1.0, currentDbfs = 0.0, blockIndex = 0, adaptiveThreshold = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sm.process(
                trigger = false,
                currentPower = Double.NaN,
                currentDbfs = 0.0,
                blockIndex = 0,
                adaptiveThreshold = 0.0
            )
        }
    }

    @Test
    fun `rejects a non-finite currentDbfs or adaptiveThreshold, or a negative blockIndex`() {
        val sm = AdaptiveDetectionStateMachine(config)

        assertThrows(IllegalArgumentException::class.java) {
            sm.process(trigger = false, currentPower = 0.0, currentDbfs = Double.NaN, blockIndex = 0, adaptiveThreshold = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sm.process(
                trigger = false,
                currentPower = 0.0,
                currentDbfs = 0.0,
                blockIndex = 0,
                adaptiveThreshold = Double.NaN
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            sm.process(trigger = false, currentPower = 0.0, currentDbfs = 0.0, blockIndex = -1, adaptiveThreshold = 0.0)
        }
    }
}
