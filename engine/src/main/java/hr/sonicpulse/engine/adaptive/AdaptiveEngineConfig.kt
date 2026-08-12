package hr.sonicpulse.engine.adaptive

data class AdaptiveEngineConfig(
    val sampleRate: Int = 44_100,
    val hopSize: Int = 1024,
    val analysisWindowSize: Int = 4096,
    val backgroundHistoryMillis: Int = 5000,
    val thresholdStdMultiplier: Double = 5.0
) {
    /** Number of causal background power observations retained, one per hop. */
    val backgroundHistoryCapacity: Int = if (hopSize > 0) {
        ((backgroundHistoryMillis.toLong() * sampleRate) / (1000L * hopSize)).toInt()
    } else {
        0
    }

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
    }
}
