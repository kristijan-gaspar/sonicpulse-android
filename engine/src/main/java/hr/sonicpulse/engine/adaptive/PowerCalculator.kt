package hr.sonicpulse.engine.adaptive

object PowerCalculator {
    private const val PCM_16_FULL_SCALE = 32768.0

    fun calculate(samples: ShortArray): Double {
        require(samples.isNotEmpty()) { "Audio samples must not be empty." }

        return samples.sumOf { sample ->
            val normalized = sample / PCM_16_FULL_SCALE
            normalized * normalized
        } / samples.size
    }

    fun calculate(window: SampleWindow): Double {
        require(window.size > 0) { "Sample window must not be empty." }

        var sumOfSquares = 0.0
        for (i in 0 until window.size) {
            val normalized = window[i] / PCM_16_FULL_SCALE
            sumOfSquares += normalized * normalized
        }
        return sumOfSquares / window.size
    }
}
