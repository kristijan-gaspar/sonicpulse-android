package hr.sonicpulse.engine

data class EngineConfig(
    val sampleRate: Int = 44_100,
    val blockSize: Int = 1024,
    val alphaDown: Double = 0.10,
    val alphaUp: Double = 0.02,
    val dbfsMin: Double = -20.0,
    val spikeMin: Double = 15.0,
    val crestMin: Double = 10.0,
    val crestWindowBlocks: Int = 3,
    val clipLevel: Int = 32_000,
    val clipRatioMin: Double = 0.02,
    val endSilenceBlocks: Int = 3,
    val cooldownBlocks: Int = 30,
    val warmupBlocks: Int = 43,
    val dbfsFloor: Double = -120.0
)