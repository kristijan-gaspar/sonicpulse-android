package hr.sonicpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class DetectionEngineTest {

    private val config = EngineConfig()

    private fun silenceBlock(size: Int = config.blockSize): ShortArray = ShortArray(size)

    private fun constantBlock(amplitude: Short, size: Int = config.blockSize): ShortArray =
        ShortArray(size) { amplitude }

    /**
     * A block that is mostly silent except for a short loud burst, tall enough to clear
     * DBFS_MIN/SPIKE_MIN and, combined with 2 quiet neighboring blocks in the crest window,
     * produce a high crest factor (a real impulse "shape").
     */
    private fun impulseBlock(size: Int = config.blockSize): ShortArray =
        ShortArray(size) { index -> if (index < 32) 20_000 else 0 }

    /** A sustained loud plateau: clips heavily, but its flat shape keeps crest low. */
    private fun clippedPlateauBlock(size: Int = config.blockSize): ShortArray =
        ShortArray(size) { index -> if (index < 600) 32_700 else 0 }

    private fun feedSilence(engine: DetectionEngine, count: Int) {
        repeat(count) { engine.process(silenceBlock()) }
    }

    @Test
    fun `synthetic silence never triggers a detection`() {
        val engine = DetectionEngine(config)

        val events = (0 until 200).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `constant loud noise never triggers because crest is too low`() {
        val engine = DetectionEngine(config)

        val events = (0 until 200).mapNotNull { engine.process(constantBlock(20_000)) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `a synthetic impulse after warmup triggers exactly one detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val impulseIndex = config.warmupBlocks.toLong()
        val duringImpulse = engine.process(impulseBlock())
        val silent1 = engine.process(silenceBlock())
        val silent2 = engine.process(silenceBlock())
        val silent3 = engine.process(silenceBlock())
        val after = (0 until 20).mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringImpulse)
        assertNull(silent1)
        assertNull(silent2)
        assertNotNull(silent3)
        assertEquals(impulseIndex, silent3!!.peakBlockIndex)
        assertEquals(emptyList<DetectionEvent>(), after)
    }

    @Test
    fun `baseline is frozen while in the DETECTING state`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val baselineAtDetectionStart = engine.currentBaseline

        // Falls back to silence but stays in DETECTING (only 1 of 3 end-silence blocks so far).
        engine.process(silenceBlock())

        assertEquals(baselineAtDetectionStart, engine.currentBaseline, 0.0)
    }

    @Test
    fun `a clipped impulse triggers via the clipping branch despite low crest`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(clippedPlateauBlock())
        val events = (0 until 3).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
    }

    @Test
    fun `an impulse train within cooldown produces only one event`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val events = mutableListOf<DetectionEvent>()
        events += listOfNotNull(engine.process(impulseBlock()))
        events += listOfNotNull(engine.process(silenceBlock()))
        events += listOfNotNull(engine.process(silenceBlock()))
        events += listOfNotNull(engine.process(silenceBlock())) // ends DETECTING, emits 1 event

        // Second impulse arrives immediately, while still inside the cooldown window.
        events += listOfNotNull(engine.process(impulseBlock()))
        repeat(config.cooldownBlocks) {
            events += listOfNotNull(engine.process(silenceBlock()))
        }

        assertEquals(1, events.size)
    }

    @Test
    fun `a new impulse after cooldown ends triggers a second, independent detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val firstEvents = mutableListOf<DetectionEvent>()
        firstEvents += listOfNotNull(engine.process(impulseBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        firstEvents += listOfNotNull(engine.process(silenceBlock()))
        repeat(config.cooldownBlocks) { engine.process(silenceBlock()) }

        val secondEvents = mutableListOf<DetectionEvent>()
        secondEvents += listOfNotNull(engine.process(impulseBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))
        secondEvents += listOfNotNull(engine.process(silenceBlock()))

        assertEquals(1, firstEvents.size)
        assertEquals(1, secondEvents.size)
    }

    @Test
    fun `process rejects an empty block`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(0))
        }
    }

    @Test
    fun `process rejects a block smaller than the configured block size`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(config.blockSize - 1))
        }
    }

    @Test
    fun `process rejects a block larger than the configured block size`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(config.blockSize + 1))
        }
    }

    @Test
    fun `process accepts a correctly sized block without throwing`() {
        val engine = DetectionEngine(config)

        engine.process(silenceBlock())
    }

    @Test
    fun `a rejected block does not seed or mutate the baseline`() {
        val engine = DetectionEngine(config)

        assertThrows(IllegalArgumentException::class.java) {
            engine.process(ShortArray(0))
        }
        assertEquals(0.0, engine.currentBaseline, 0.0)

        engine.process(silenceBlock())
        assertEquals(config.dbfsFloor, engine.currentBaseline, 0.0)
    }

    @Test
    fun `rejected blocks do not advance the processed block index used for warmup gating`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks - 1)

        repeat(5) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.process(ShortArray(config.blockSize - 1))
            }
        }

        val events = mutableListOf<DetectionEvent>()
        events += listOfNotNull(engine.process(impulseBlock()))
        events += (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(emptyList<DetectionEvent>(), events)
    }

    @Test
    fun `spike is computed against the baseline as it stood before this block, not after`() {
        val burstSize = 32
        val seedAmplitude = 1000

        fun burstBlock(amplitude: Int): ShortArray =
            ShortArray(config.blockSize) { index -> if (index < burstSize) amplitude.toShort() else 0 }

        fun dbfsOf(rms: Double): Double = 20.0 * log10(rms / 32768.0)

        val seedDbfs = dbfsOf(seedAmplitude.toDouble())

        // A target spike that, computed against the OLD (pre-update) baseline, clears
        // spikeMin — but that the buggy ordering (this block's own dBFS shrinking the
        // spike via an alphaUp-sized baseline nudge first) would pull back under spikeMin.
        // Midpoint of the window (spikeMin, spikeMin / (1 - alphaUp)) where exactly this happens.
        val targetSpike = config.spikeMin / (1 - config.alphaUp / 2)
        val buggySpike = targetSpike * (1 - config.alphaUp)
        check(buggySpike < config.spikeMin) {
            "test setup invalid: buggySpike=$buggySpike must fall under spikeMin=${config.spikeMin}"
        }

        val impulseDbfs = seedDbfs + targetSpike
        check(impulseDbfs > config.dbfsMin) {
            "test setup invalid: impulseDbfs=$impulseDbfs must clear dbfsMin=${config.dbfsMin}"
        }
        val burstRms = 32768.0 * 10.0.pow(impulseDbfs / 20.0)
        val impulseAmplitude = (burstRms * sqrt(config.blockSize.toDouble() / burstSize)).toInt()
        check(impulseAmplitude in 1..32_767) {
            "test setup invalid: impulseAmplitude=$impulseAmplitude out of Short range"
        }

        val engine = DetectionEngine(config)
        repeat(config.warmupBlocks) {
            engine.process(ShortArray(config.blockSize) { seedAmplitude.toShort() })
        }
        check(engine.currentBaseline == seedDbfs) {
            "test setup invalid: baseline did not converge to seedDbfs, was ${engine.currentBaseline}"
        }

        engine.process(burstBlock(impulseAmplitude))
        val closingEvents = (0 until config.endSilenceBlocks).mapNotNull {
            engine.process(ShortArray(config.blockSize) { seedAmplitude.toShort() })
        }

        assertEquals(1, closingEvents.size)
    }

    @Test
    fun `a later louder block that fails the full trigger still becomes the tracked peak`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        fun burstDbfs(amplitude: Int, burstSize: Int): Double {
            val rms = amplitude * sqrt(burstSize.toDouble() / config.blockSize)
            return 20.0 * log10(rms / 32768.0)
        }

        val impulseIndex = config.warmupBlocks.toLong()
        val impulseDbfs = burstDbfs(amplitude = 20_000, burstSize = 32) // matches impulseBlock()'s shape

        // A loud, sustained plateau: louder overall than the impulse, but its flat shape
        // (crest ~0) fails the full onset trigger, so it must not itself start a new event —
        // it should only extend the peak of the one already open.
        val plateauAmplitude = 25_000
        val plateauDbfs = 20.0 * log10(plateauAmplitude / 32768.0)
        check(plateauDbfs > impulseDbfs) {
            "test setup invalid: plateau ($plateauDbfs dBFS) must be louder than the impulse ($impulseDbfs dBFS)"
        }

        fun loudPlateauBlock(): ShortArray = ShortArray(config.blockSize) { plateauAmplitude.toShort() }

        engine.process(impulseBlock())
        val plateauIndex = impulseIndex + 1
        engine.process(loudPlateauBlock())

        val events = (0 until config.endSilenceBlocks - 1).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
        assertEquals(plateauDbfs, events.single().peakDbfs, 1e-9)
        assertEquals(plateauIndex, events.single().peakBlockIndex)
    }

    @Test
    fun `the last warmup block still suppresses triggering`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks - 1)

        val duringLastWarmupBlock = engine.process(impulseBlock())
        val followUp = (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringLastWarmupBlock)
        assertEquals(emptyList<DetectionEvent>(), followUp)
    }

    @Test
    fun `the first post-warmup block is eligible to trigger`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val events = (0 until config.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, events.size)
    }

    @Test
    fun `one non-trigger block does not end an open detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val result = engine.process(silenceBlock())

        assertNull(result)
    }

    @Test
    fun `endSilenceBlocks minus one consecutive non-trigger blocks do not end an open detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val results = (0 until config.endSilenceBlocks - 1).map { engine.process(silenceBlock()) }

        assertEquals(List(config.endSilenceBlocks - 1) { null }, results)
    }

    @Test
    fun `exactly endSilenceBlocks consecutive non-trigger blocks end the detection`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        val results = (0 until config.endSilenceBlocks).map { engine.process(silenceBlock()) }

        assertEquals(config.endSilenceBlocks - 1, results.count { it == null })
        assertNotNull(results.last())
    }

    @Test
    fun `a new trigger before reaching endSilenceBlocks resets the non-trigger counter`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        engine.process(silenceBlock()) // 1 non-trigger block toward the count
        engine.process(impulseBlock()) // re-triggers mid-DETECTING, resets the counter to 0

        val stillOpen = engine.process(silenceBlock()) // this is only the 1st non-trigger block since the reset
        val closing = (0 until config.endSilenceBlocks - 1).mapNotNull { engine.process(silenceBlock()) }

        assertNull(stillOpen)
        assertEquals(1, closing.size)
    }

    @Test
    fun `an impulse during COOLDOWN is ignored and does not start a new event`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN

        val duringCooldown = engine.process(impulseBlock())
        val remainder = (0 until config.cooldownBlocks + config.endSilenceBlocks + 5)
            .mapNotNull { engine.process(silenceBlock()) }

        assertNull(duringCooldown)
        assertEquals(emptyList<DetectionEvent>(), remainder)
    }

    @Test
    fun `cooldown completes after exactly cooldownBlocks and the final block is not re-evaluated as IDLE`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        engine.process(impulseBlock())
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN
        repeat(config.cooldownBlocks - 1) { engine.process(silenceBlock()) } // one block short of IDLE

        // This block is the cooldownBlocks-th cooldown block: state flips to IDLE by the end of
        // processing it, but the block itself was dispatched while still COOLDOWN and must not
        // be retroactively evaluated as a fresh IDLE trigger, despite being a loud impulse.
        val finalCooldownBlock = engine.process(impulseBlock())
        val afterCooldown = (0 until config.endSilenceBlocks + 5).mapNotNull { engine.process(silenceBlock()) }

        assertNull(finalCooldownBlock)
        assertEquals(emptyList<DetectionEvent>(), afterCooldown)
    }

    @Test
    fun `baseline is frozen while in the COOLDOWN state`() {
        val ambientAmplitude: Short = 50
        fun ambientBlock(): ShortArray = ShortArray(config.blockSize) { ambientAmplitude }

        val engine = DetectionEngine(config)
        repeat(config.warmupBlocks) { engine.process(ambientBlock()) }
        val baselineBeforeEvent = engine.currentBaseline

        engine.process(impulseBlock()) // far louder than ambient; triggers and opens DETECTING
        repeat(config.endSilenceBlocks) { engine.process(silenceBlock()) } // closes event, enters COOLDOWN

        // Silence is much quieter than the ambient baseline; if COOLDOWN did not freeze the
        // baseline, this block would pull it toward the floor via alphaDown.
        engine.process(silenceBlock())

        assertEquals(baselineBeforeEvent, engine.currentBaseline, 0.0)
    }

    @Test
    fun `a single impulse produces exactly one DetectionEvent, not zero or multiple`() {
        val engine = DetectionEngine(config)
        feedSilence(engine, config.warmupBlocks)

        val events = mutableListOf<DetectionEvent?>()
        events += engine.process(impulseBlock())
        events += (0 until config.endSilenceBlocks + 10).map { engine.process(silenceBlock()) }

        assertEquals(1, events.count { it != null })
    }

    @Test
    fun `cooldownBlocks of zero skips COOLDOWN entirely, allowing the very next block to start a new event`() {
        val zeroCooldownConfig = EngineConfig(cooldownBlocks = 0)
        val engine = DetectionEngine(zeroCooldownConfig)
        feedSilence(engine, zeroCooldownConfig.warmupBlocks)

        val firstEvents = mutableListOf<DetectionEvent>()
        firstEvents += listOfNotNull(engine.process(impulseBlock()))
        firstEvents += (0 until zeroCooldownConfig.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        // No blocks should be ignored: the impulse immediately following the first event's
        // close must itself start a second, independent detection.
        val secondEvents = mutableListOf<DetectionEvent>()
        secondEvents += listOfNotNull(engine.process(impulseBlock()))
        secondEvents += (0 until zeroCooldownConfig.endSilenceBlocks).mapNotNull { engine.process(silenceBlock()) }

        assertEquals(1, firstEvents.size)
        assertEquals(1, secondEvents.size)
    }
}
