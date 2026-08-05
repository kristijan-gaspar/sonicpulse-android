package hr.sonicpulse.engine

/**
 * [blockIndex] mirrors [DetectionEngine]'s own internal processed-block counter at the moment
 * this block was analyzed — exposed so callers (e.g. session-level diagnostics) can correlate a
 * block with a [DetectionEvent.peakBlockIndex] without keeping a second counter of their own in
 * lockstep with the engine's. Purely observational: nothing about trigger/state-machine behavior
 * reads or depends on it. Defaults to 0 so existing call sites that don't care about it (most
 * tests) are unaffected.
 */
data class BlockMetrics(
    val rms: Double,
    val dbfs: Double,
    val baseline: Double,
    val spike: Double,
    val crest: Double?,
    val clipRatio: Double,
    val state: DetectionState,
    val blockIndex: Long = 0L
)
