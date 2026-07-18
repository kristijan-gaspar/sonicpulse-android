package hr.sonicpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
