package hr.sonicpulse.engine.metrics

import kotlin.math.abs

object ClippingCalculator {

    private const val PCM_16_MAX_ABSOLUTE_MAGNITUDE = 32_768

    fun calculateClipRatio(samples: ShortArray, clipLevel: Int): Double {
        require(samples.isNotEmpty()) {
            "Audio samples must not be empty."
        }
        require(clipLevel in 1..PCM_16_MAX_ABSOLUTE_MAGNITUDE) {
            "Clip level must be between 1 and $PCM_16_MAX_ABSOLUTE_MAGNITUDE, was $clipLevel."
        }

        val clippedCount = samples.count { sample -> abs(sample.toInt()) >= clipLevel }
        return clippedCount.toDouble() / samples.size
    }
}