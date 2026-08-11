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
}
