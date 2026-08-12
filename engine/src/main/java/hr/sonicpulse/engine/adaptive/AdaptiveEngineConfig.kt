package hr.sonicpulse.engine.adaptive

data class AdaptiveEngineConfig(
    val sampleRate: Int = 44_100,
    val hopSize: Int = 1024,
    val analysisWindowSize: Int = 4096,
    val backgroundHistoryMillis: Int = 5000,
    val thresholdStdMultiplier: Double = 5.0,
    val variationHistoryMillis: Int = 5000,
    /**
     * Dufaux's overshoot factor `ov` for the Eq. 3.9 adaptive variation threshold
     * `th(k) = ov * max(recent variation)`. `1.5` is the value Dufaux uses as an example
     * for this adaptive background-variation threshold; it is an initial,
     * literature-grounded project default for SonicPulse, not a claim of universal
     * optimality, and remains tunable.
     */
    val ov: Double = 1.5
) {
    /**
     * Number of causal background power observations retained, one per hop (Dufaux's `L`).
     * Rounded up so the retained history always covers at least [backgroundHistoryMillis].
     */
    val backgroundHistoryCapacity: Int = ceilingCapacity(backgroundHistoryMillis, sampleRate, hopSize)

    /**
     * Number of causal background-variation observations retained for the Eq. 3.9 rolling
     * maximum (Dufaux's `D`). Kept as a separate config capacity from
     * [backgroundHistoryCapacity] even though both default to the same ~5 s duration and
     * therefore the same count.
     */
    val variationHistoryCapacity: Int = ceilingCapacity(variationHistoryMillis, sampleRate, hopSize)

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate." }
        require(hopSize > 0) { "hopSize must be positive, was $hopSize." }
        require(analysisWindowSize > 0) {
            "analysisWindowSize must be positive, was $analysisWindowSize."
        }
        require(analysisWindowSize >= hopSize) {
            "analysisWindowSize must be >= hopSize, was analysisWindowSize=$analysisWindowSize, " +
                "hopSize=$hopSize."
        }
        require(analysisWindowSize % hopSize == 0) {
            "analysisWindowSize must be a whole multiple of hopSize, so the rolling window " +
                "fills and slides on exact hop boundaries, was analysisWindowSize=$analysisWindowSize, " +
                "hopSize=$hopSize."
        }
        require(backgroundHistoryMillis > 0) {
            "backgroundHistoryMillis must be positive, was $backgroundHistoryMillis."
        }
        require(thresholdStdMultiplier > 0.0) {
            "thresholdStdMultiplier must be positive, was $thresholdStdMultiplier."
        }
        require(backgroundHistoryCapacity > 0) {
            "backgroundHistoryCapacity derived from backgroundHistoryMillis=$backgroundHistoryMillis, " +
                "sampleRate=$sampleRate, hopSize=$hopSize must be at least 1; increase " +
                "backgroundHistoryMillis or decrease hopSize."
        }
        require(variationHistoryMillis > 0) {
            "variationHistoryMillis must be positive, was $variationHistoryMillis."
        }
        require(variationHistoryCapacity > 0) {
            "variationHistoryCapacity derived from variationHistoryMillis=$variationHistoryMillis, " +
                "sampleRate=$sampleRate, hopSize=$hopSize must be at least 1; increase " +
                "variationHistoryMillis or decrease hopSize."
        }
        require(ov > 0.0) { "ov must be positive, was $ov." }
    }

    private companion object {
        fun ceilingCapacity(millis: Int, sampleRate: Int, hopSize: Int): Int {
            if (hopSize <= 0) return 0
            val numerator = millis.toLong() * sampleRate
            val denominator = 1000L * hopSize
            return ((numerator + denominator - 1) / denominator).toInt()
        }
    }
}
