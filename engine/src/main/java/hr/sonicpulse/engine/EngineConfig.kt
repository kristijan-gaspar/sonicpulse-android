package hr.sonicpulse.engine

data class EngineConfig(
    val sampleRate: Int = 44_100,
    val blockSize: Int = 1024,
    val alphaDown: Double = 0.10,
    val alphaUp: Double = 0.02,
    val dbfsMin: Double = -20.0,
    val spikeMin: Double = 15.0,
    val releaseDropDb: Double = 20.0,
    val crestMin: Double = 10.0,
    val crestWindowBlocks: Int = 3,
    val clipLevel: Int = 32_000,
    val clipRatioMin: Double = 0.02,
    val endSilenceBlocks: Int = 3,
    val maxEventDurationBlocks: Int = 30,
    val cooldownBlocks: Int = 30,
    val rejectedCooldownBlocks: Int = 5,
    val warmupBlocks: Int = 43,
    val dbfsFloor: Double = -120.0
) {
    companion object {
        private const val PCM_16_MAX_ABSOLUTE_MAGNITUDE = 32_768
    }

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate." }
        require(blockSize > 0) { "blockSize must be positive, was $blockSize." }

        require(alphaDown.isFinite() && alphaDown > 0.0 && alphaDown <= 1.0) {
            "alphaDown must be finite and within (0.0, 1.0], was $alphaDown."
        }
        require(alphaUp.isFinite() && alphaUp > 0.0 && alphaUp <= 1.0) {
            "alphaUp must be finite and within (0.0, 1.0], was $alphaUp."
        }
        require(alphaDown > alphaUp) {
            "alphaDown must be greater than alphaUp (asymmetric EMA: fast down, slow up), " +
                "was alphaDown=$alphaDown, alphaUp=$alphaUp."
        }

        require(dbfsFloor.isFinite() && dbfsFloor < 0.0) {
            "dbfsFloor must be finite and below zero, was $dbfsFloor."
        }
        require(dbfsMin.isFinite() && dbfsMin < 0.0 && dbfsMin > dbfsFloor) {
            "dbfsMin must be finite, below zero, and above dbfsFloor ($dbfsFloor), was $dbfsMin."
        }

        require(spikeMin.isFinite() && spikeMin > 0.0) {
            "spikeMin must be finite and positive, was $spikeMin."
        }
        require(releaseDropDb.isFinite() && releaseDropDb > 0.0) {
            "releaseDropDb must be finite and strictly positive, was $releaseDropDb."
        }
        // The quietest possible onset peak sits just above dbfsMin, so a releaseDropDb larger
        // than the dbfsMin-to-dbfsFloor range would put the release boundary below dbfsFloor —
        // meaning even digital silence stays "still active" and a candidate could only ever end
        // via the hard maxEventDurationBlocks/TOO_LONG limit, never a normal release. Equal to
        // the range is fine: the release comparison is strict `>`, so silence at exactly
        // dbfsFloor is inactive against a boundary of exactly dbfsFloor.
        val maxReleaseDropDb = dbfsMin - dbfsFloor
        require(releaseDropDb <= maxReleaseDropDb) {
            "releaseDropDb must not exceed the available dBFS range " +
                "between dbfsMin ($dbfsMin) and dbfsFloor ($dbfsFloor), " +
                "maximum is $maxReleaseDropDb, was $releaseDropDb."
        }
        require(crestMin.isFinite() && crestMin >= 0.0) {
            "crestMin must be finite and non-negative, was $crestMin."
        }
        require(crestWindowBlocks > 0) {
            "crestWindowBlocks must be positive, was $crestWindowBlocks."
        }

        require(clipLevel in 1..PCM_16_MAX_ABSOLUTE_MAGNITUDE) {
            "clipLevel must be between 1 and $PCM_16_MAX_ABSOLUTE_MAGNITUDE, was $clipLevel."
        }
        require(clipRatioMin.isFinite() && clipRatioMin in 0.0..1.0) {
            "clipRatioMin must be finite and within 0.0..1.0, was $clipRatioMin."
        }

        require(endSilenceBlocks > 0) {
            "endSilenceBlocks must be positive, was $endSilenceBlocks."
        }
        require(maxEventDurationBlocks > 0) {
            "maxEventDurationBlocks must be positive, was $maxEventDurationBlocks."
        }
        require(cooldownBlocks >= 0) {
            "cooldownBlocks must not be negative, was $cooldownBlocks."
        }
        require(rejectedCooldownBlocks >= 0) {
            "rejectedCooldownBlocks must not be negative, was $rejectedCooldownBlocks."
        }
        require(warmupBlocks >= 0) {
            "warmupBlocks must not be negative, was $warmupBlocks."
        }
    }
}