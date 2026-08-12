package hr.sonicpulse.engine.adaptive

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Single-hop crest factor (peak / RMS, in dB): `20 * log10(peak / rms)`. Unlike V1's
 * `hr.sonicpulse.engine.metrics.CrestFactorTracker`, this has no rolling multi-block
 * window — V2's onset semantics only need the current hop's own crest factor, at the
 * same 1024-sample granularity V1 uses per block. Returns `null` for pure silence
 * (rms == 0), where crest factor is undefined.
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
}
