package hr.sonicpulse.engine.metrics

import kotlin.math.abs

object ClippingCalculator {

    fun calculateClipRatio(samples: ShortArray, clipLevel: Int): Double {
        val clippedCount = samples.count { sample -> abs(sample.toInt()) >= clipLevel }
        return clippedCount.toDouble() / samples.size
    }
}