package hr.sonicpulse.engine.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioLevelCalculator {
    private const val PCM_16_FULL_SCALE = 32768.0
    private const val DBFS_FLOOR = -120.0

    fun calculate(samples: ShortArray, floor: Double = DBFS_FLOOR): AudioLevel {
        validateSamples(samples)

        val rms = calculateRms(samples)
        val normalizedRms = normalizeRms(rms)
        val dbfs = convertToDbfs(normalizedRms, floor)

        return AudioLevel(
            rms = rms,
            dbfs = dbfs
        )
    }

    private fun validateSamples(samples: ShortArray) {
        require(samples.isNotEmpty()) {
            "Audio samples must not be empty."
        }
    }

    private fun calculateRms(samples: ShortArray): Double {
        val meanSquare = samples.sumOf { sample ->
            val value = sample.toDouble()
            value * value
        } / samples.size

        return sqrt(meanSquare)
    }

    private fun normalizeRms(rms: Double): Double {
        return rms / PCM_16_FULL_SCALE
    }

    private fun convertToDbfs(normalizedRms: Double, floor: Double): Double {
        return if (normalizedRms == 0.0) {
            floor
        } else {
            (20.0 * log10(normalizedRms)).coerceAtLeast(floor)
        }
    }
}