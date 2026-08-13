package hr.sonicpulse.engine.adaptive

data class AdaptiveEngineConfig(
    val sampleRate: Int = 44_100,
    val hopSize: Int = 1024,
    val analysisWindowSize: Int = 4096,
    val backgroundHistoryMillis: Int = 5000,
    val initialThaStdMultiplier: Double = 5.0,
    val variationHistoryMillis: Int = 18_500,
    val variationWarmupMillis: Int = 5000,

    val minRelativePowerRiseDb: Double = 18.0,

    val ov: Double = 1.5,
    /** Minimum crest factor (peak/RMS, dB) for a hop to count as impulsive. */
    val crestMinDb: Double = 10.0,
    /** Absolute PCM16 sample magnitude at/above which a sample counts as clipped. */
    val clipLevel: Int = 32_000,
    /** Minimum fraction of clipped samples in a hop for it to count as impulsive. */
    val clipRatioMin: Double = 0.02,
    /** Consecutive inactive hops in DETECTING before a candidate event is accepted. */
    val endSilenceHops: Int = 3,
    /** Maximum candidate event duration before it is rejected as too long. */
    val maxEventDurationMillis: Int = 300,
    /** Cooldown duration after leaving DETECTING (accepted or rejected alike). */
    val cooldownMillis: Int = 700,
    // Short cooldown after a rejected candidate.
    val rejectedCooldownHops: Int = 5
) {
    /**
     * Number of causal background power observations retained, one per hop (Dufaux's `L`).
     * Rounded up so the retained history always covers at least [backgroundHistoryMillis].
     */
    val backgroundHistoryCapacity: Int = ceilingCapacity(backgroundHistoryMillis, sampleRate, hopSize)

    /**
     * Number of causal background-variation observations retained for the Eq. 3.9 rolling
     * maximum (Dufaux's `D`). Deliberately a much longer retention window than
     * [backgroundHistoryCapacity] by default (~18.5 s vs ~5 s) — see [variationWarmupCapacity]
     * for the separate, shorter warm-up gate that lets detection start well before this full
     * `D`-sized window fills.
     */
    val variationHistoryCapacity: Int = ceilingCapacity(variationHistoryMillis, sampleRate, hopSize)

    /**
     * Number of variation observations required before the rolling Eq. 3.9 threshold is
     * considered warmed up enough to gate real detection ([RobustVariationThresholdHistory.isReady]).
     * Deliberately a shorter warmup than [variationHistoryCapacity] (the full `D`-sized rolling
     * window), so the adaptive engine does not have to wait for the whole D window to fill
     * before it can start triggering.
     */
    val variationWarmupCapacity: Int = ceilingCapacity(variationWarmupMillis, sampleRate, hopSize)

    /** [maxEventDurationMillis] rounded up to whole hops. */
    val maxEventDurationHops: Int = ceilingCapacity(maxEventDurationMillis, sampleRate, hopSize)

    /** [cooldownMillis] rounded up to whole hops. */
    val cooldownHops: Int = ceilingCapacity(cooldownMillis, sampleRate, hopSize)

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
        require(initialThaStdMultiplier.isFinite() && initialThaStdMultiplier > 0.0) {
            "initialThaStdMultiplier must be finite and positive, was $initialThaStdMultiplier."
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
        require(variationWarmupMillis > 0) {
            "variationWarmupMillis must be positive, was $variationWarmupMillis."
        }
        require(variationWarmupCapacity > 0) {
            "variationWarmupCapacity derived from variationWarmupMillis=$variationWarmupMillis, " +
                "sampleRate=$sampleRate, hopSize=$hopSize must be at least 1; increase " +
                "variationWarmupMillis or decrease hopSize."
        }
        require(variationWarmupCapacity <= variationHistoryCapacity) {
            "variationWarmupCapacity ($variationWarmupCapacity, from variationWarmupMillis=" +
                "$variationWarmupMillis) must be <= variationHistoryCapacity " +
                "($variationHistoryCapacity, from variationHistoryMillis=$variationHistoryMillis) " +
                "- warm-up cannot require more variations than the D window can ever retain."
        }
        require(ov > 0.0) { "ov must be positive, was $ov." }
        require(minRelativePowerRiseDb.isFinite() && minRelativePowerRiseDb >= 0.0) {
            "minRelativePowerRiseDb must be finite and non-negative, was $minRelativePowerRiseDb."
        }
        require(crestMinDb.isFinite() && crestMinDb >= 0.0) {
            "crestMinDb must be finite and non-negative, was $crestMinDb."
        }
        require(clipLevel in 1..PCM_16_MAX_ABSOLUTE_MAGNITUDE) {
            "clipLevel must be between 1 and $PCM_16_MAX_ABSOLUTE_MAGNITUDE, was $clipLevel."
        }
        require(clipRatioMin.isFinite() && clipRatioMin in 0.0..1.0) {
            "clipRatioMin must be finite and within 0.0..1.0, was $clipRatioMin."
        }
        require(endSilenceHops > 0) { "endSilenceHops must be positive, was $endSilenceHops." }
        require(maxEventDurationMillis > 0) {
            "maxEventDurationMillis must be positive, was $maxEventDurationMillis."
        }
        require(cooldownMillis > 0) { "cooldownMillis must be positive, was $cooldownMillis." }
        require(maxEventDurationHops > 0) {
            "maxEventDurationHops derived from maxEventDurationMillis=$maxEventDurationMillis, " +
                "sampleRate=$sampleRate, hopSize=$hopSize must be at least 1."
        }
        require(cooldownHops > 0) {
            "cooldownHops derived from cooldownMillis=$cooldownMillis, sampleRate=$sampleRate, " +
                "hopSize=$hopSize must be at least 1."
        }
        require(rejectedCooldownHops >= 0) {
            "rejectedCooldownHops must be non-negative, was $rejectedCooldownHops."
        }
    }

    private companion object {
        const val PCM_16_MAX_ABSOLUTE_MAGNITUDE = 32_768

        fun ceilingCapacity(millis: Int, sampleRate: Int, hopSize: Int): Int {
            if (hopSize <= 0) return 0
            val numerator = millis.toLong() * sampleRate
            val denominator = 1000L * hopSize
            return ((numerator + denominator - 1) / denominator).toInt()
        }
    }
}
