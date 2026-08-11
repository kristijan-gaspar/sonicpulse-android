package hr.sonicpulse.engine.adaptive

data class AdaptiveEngineConfig(
    val sampleRate: Int = 44_100,
    val hopSize: Int = 1024,
    val analysisWindowSize: Int = 4096
) {
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
    }
}
