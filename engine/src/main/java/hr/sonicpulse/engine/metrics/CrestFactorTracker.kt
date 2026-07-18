package hr.sonicpulse.engine.metrics

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class CrestFactorTracker(private val windowBlocks: Int) {

    private data class BlockStats(val peak: Int, val sumOfSquares: Double, val sampleCount: Int)

    private val window = ArrayDeque<BlockStats>()

    fun addBlock(samples: ShortArray) {
        val peak = samples.maxOf { sample -> abs(sample.toInt()) }
        val sumOfSquares = samples.sumOf { sample -> sample.toDouble() * sample.toDouble() }

        window.addLast(BlockStats(peak, sumOfSquares, samples.size))
        if (window.size > windowBlocks) {
            window.removeFirst()
        }
    }

    fun currentCrest(): Double? {
        val peak = window.maxOf { it.peak }
        val totalSumOfSquares = window.sumOf { it.sumOfSquares }
        val totalSamples = window.sumOf { it.sampleCount }

        val rms3 = sqrt(totalSumOfSquares / totalSamples)
        if (rms3 == 0.0) return null

        return 20.0 * log10(peak / rms3)
    }
}
