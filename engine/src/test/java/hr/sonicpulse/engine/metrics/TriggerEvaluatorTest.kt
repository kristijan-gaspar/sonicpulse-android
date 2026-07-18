package hr.sonicpulse.engine.metrics

import hr.sonicpulse.engine.EngineConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEvaluatorTest {

    private val config = EngineConfig()

    @Test
    fun `triggers when dbfs, spike and crest all clear their thresholds`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = 20.0, crest = 12.0, clipRatio = 0.0, config = config
        )

        assertTrue(result)
    }

    @Test
    fun `does not trigger when dbfs is at or below the absolute floor`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = config.dbfsMin, spike = 20.0, crest = 12.0, clipRatio = 0.0, config = config
        )

        assertFalse(result)
    }

    @Test
    fun `does not trigger when spike is at or below its threshold`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = config.spikeMin, crest = 12.0, clipRatio = 0.0, config = config
        )

        assertFalse(result)
    }

    @Test
    fun `does not trigger when crest is too low and clipping does not bypass it`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = 20.0, crest = 2.0, clipRatio = 0.0, config = config
        )

        assertFalse(result)
    }

    @Test
    fun `clipping ratio above threshold bypasses a failing crest condition`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = 20.0, crest = 2.0, clipRatio = 0.05, config = config
        )

        assertTrue(result)
    }

    @Test
    fun `clipping ratio bypasses crest even when crest could not be computed at all`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = 20.0, crest = null, clipRatio = 0.05, config = config
        )

        assertTrue(result)
    }

    @Test
    fun `crest above threshold triggers even without any clipping`() {
        val result = TriggerEvaluator.shouldTrigger(
            dbfs = -5.0, spike = 20.0, crest = 12.0, clipRatio = 0.0, config = config
        )

        assertTrue(result)
    }
}
