package hr.sonicpulse.engine.adaptive

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Crest factor (peak / RMS, in dB): `20 * log10(peak / rms)`. Unlike V1's
 * `hr.sonicpulse.engine.metrics.CrestFactorTracker`, this has no rolling multi-block
 * window of its own — it simply works over whatever sample array or [SampleWindow] the
 * caller supplies, at any granularity (the current 1024-sample hop, the 4096-sample
 * rolling analysis window, or otherwise). Returns `null` for pure silence (rms == 0),
 * where crest factor is undefined.
 */
object CrestFactorCalculator {

    fun calculate(samples: ShortArray): Double? {
        require(samples.isNotEmpty()) { "Audio samples must not be empty." }

        val peak = samples.maxOf { sample -> abs(sample.toInt()) }
        val meanSquare = samples.sumOf { sample ->
            val value = sample.toDouble()
            value * value
        } / samples.size
        val rms = sqrt(meanSquare)

        if (rms == 0.0) return null
        return 20.0 * log10(peak / rms)
    }

    fun calculate(window: SampleWindow): Double? {
        require(window.size > 0) { "Sample window must not be empty." }

        var peak = 0
        var sumOfSquares = 0.0
        for (i in 0 until window.size) {
            val sample = window[i]
            val absSample = abs(sample.toInt())
            if (absSample > peak) peak = absSample
            val value = sample.toDouble()
            sumOfSquares += value * value
        }
        val rms = sqrt(sumOfSquares / window.size)

        if (rms == 0.0) return null
        return 20.0 * log10(peak / rms)
    }
}
