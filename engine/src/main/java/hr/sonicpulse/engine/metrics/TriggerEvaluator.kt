package hr.sonicpulse.engine.metrics

import hr.sonicpulse.engine.EngineConfig

object TriggerEvaluator {

    fun shouldTrigger(
        dbfs: Double,
        spike: Double,
        crest: Double?,
        clipRatio: Double,
        config: EngineConfig
    ): Boolean {
        val loudEnough = dbfs > config.dbfsMin
        val spiked = spike > config.spikeMin
        val impulsive = (crest != null && crest > config.crestMin) || clipRatio > config.clipRatioMin

        return loudEnough && spiked && impulsive
    }
}
