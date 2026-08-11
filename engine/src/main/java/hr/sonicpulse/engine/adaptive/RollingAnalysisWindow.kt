package hr.sonicpulse.engine.adaptive

/**
 * Accumulates hops into a fixed-capacity ring buffer and exposes the most recent
 * [AdaptiveEngineConfig.analysisWindowSize] samples as a chronologically ordered
 * [SampleWindow], without physically shifting retained samples on every hop.
 */
class RollingAnalysisWindow(private val config: AdaptiveEngineConfig) {

    private val buffer = ShortArray(config.analysisWindowSize)

    /** Index of the oldest retained sample; also where the next hop is written. */
    private var writeIndex = 0
    private var filledSamples = 0

    private val view = object : SampleWindow {
        override val size: Int get() = config.analysisWindowSize

        override fun get(index: Int): Short {
            if (index !in 0 until config.analysisWindowSize) {
                throw IndexOutOfBoundsException(
                    "index must be within 0 until ${config.analysisWindowSize}, was $index."
                )
            }
            return buffer[(writeIndex + index) % config.analysisWindowSize]
        }
    }

    fun update(hop: ShortArray): SampleWindow? {
        require(hop.size == config.hopSize) {
            "hop must have exactly ${config.hopSize} samples, was ${hop.size}."
        }

        writeHop(hop)
        filledSamples = (filledSamples + config.hopSize).coerceAtMost(config.analysisWindowSize)

        return if (filledSamples < config.analysisWindowSize) null else view
    }

    fun reset() {
        buffer.fill(0)
        writeIndex = 0
        filledSamples = 0
    }

    private fun writeHop(hop: ShortArray) {
        val firstRunLength = minOf(hop.size, config.analysisWindowSize - writeIndex)
        hop.copyInto(buffer, destinationOffset = writeIndex, startIndex = 0, endIndex = firstRunLength)
        if (firstRunLength < hop.size) {
            hop.copyInto(buffer, destinationOffset = 0, startIndex = firstRunLength, endIndex = hop.size)
        }
        writeIndex = (writeIndex + hop.size) % config.analysisWindowSize
    }
}
