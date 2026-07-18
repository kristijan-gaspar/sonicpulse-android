package hr.sonicpulse.engine

data class DetectionEvent(
    val peakDbfs: Double,
    val peakBlockIndex: Long
)
