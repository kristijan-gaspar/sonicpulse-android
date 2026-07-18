package hr.sonicpulse.engine.metrics

object SpikeCalculator {

    fun calculateSpike(dbfs: Double, baseline: Double): Double {
        return dbfs - baseline
    }
}