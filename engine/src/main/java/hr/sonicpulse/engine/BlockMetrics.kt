package hr.sonicpulse.engine

data class BlockMetrics(
    val rms: Double,
    val dbfs: Double,
    val baseline: Double,
    val spike: Double,
    val crest: Double?,
    val clipRatio: Double,
    val state: DetectionState
)
