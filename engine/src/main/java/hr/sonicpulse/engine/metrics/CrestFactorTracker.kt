package hr.sonicpulse.engine.metrics

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class CrestFactorTracker(
    private val windowBlocks: Int
) {

    init {
        require(windowBlocks > 0) {
            "Window size must be greater than zero."
        }
    }

    private data class BlockStats(
        val peak: Int,
        val sumOfSquares: Double,
        val sampleCount: Int
    )

    private val window = ArrayDeque<BlockStats>()

    fun addBlock(samples: ShortArray) {
        require(samples.isNotEmpty()) {
            "Audio samples must not be empty."
        }

        val peak = samples.maxOf { sample ->
            abs(sample.toInt())
        }

        val sumOfSquares = samples.sumOf { sample ->
            val value = sample.toDouble()
            value * value
        }

        window.addLast(
            BlockStats(
                peak = peak,
                sumOfSquares = sumOfSquares,
                sampleCount = samples.size
            )
        )

        if (window.size > windowBlocks) {
            window.removeFirst()
        }
    }

    fun currentCrest(): Double? {
        if (window.isEmpty()) {
            return null
        }

        val peak = window.maxOf { it.peak }
        val totalSumOfSquares = window.sumOf { it.sumOfSquares }
        val totalSamples = window.sumOf { it.sampleCount }

        val rms = sqrt(totalSumOfSquares / totalSamples)

        if (rms == 0.0) {
            return null
        }

        return 20.0 * log10(peak / rms)
    }
}