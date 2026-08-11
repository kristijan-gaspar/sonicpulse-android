package hr.sonicpulse.engine.adaptive

class RollingAnalysisWindow(private val config: AdaptiveEngineConfig) {

    private val buffer = ShortArray(config.analysisWindowSize)
    private var filledSamples = 0

    fun update(hop: ShortArray): ShortArray? {
        require(hop.size == config.hopSize) {
            "hop must have exactly ${config.hopSize} samples, was ${hop.size}."
        }

        val retainedSamples = config.analysisWindowSize - config.hopSize
        System.arraycopy(buffer, config.hopSize, buffer, 0, retainedSamples)
        hop.copyInto(buffer, retainedSamples)

        filledSamples = (filledSamples + config.hopSize).coerceAtMost(config.analysisWindowSize)

        return if (filledSamples < config.analysisWindowSize) null else buffer.copyOf()
    }

    fun reset() {
        buffer.fill(0)
        filledSamples = 0
    }
}
