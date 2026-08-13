package hr.sonicpulse.app.detection

import hr.sonicpulse.app.observability.DetectionSessionLogger

/**
 * [DetectionProcessor] decorator that wires the V2 per-hop session logger into the
 * detection runtime: wraps a concrete [AdaptiveDetectionProcessor], delegates every
 * [process] call to it unchanged, and — only once that call has actually returned — reports
 * that exact same hop's [AdaptiveDetectionProcessor.lastDiagnostics] (and the
 * [DetectionProcessingResult.event] that closed on it, if any) to [logger] via
 * [DetectionSessionLogger.onHop].
 *
 * Knows nothing about JSON, session-log documents, `BuildConfig`, retention limits, or
 * config snapshots — those are entirely [logger]'s concern. If [delegate].process throws,
 * [logger] is never called: the exception propagates unchanged, and no diagnostics from a
 * *previous* hop are ever reused or reported for a failed one — [lastDiagnostics] is only
 * ever read immediately after a [delegate].process call that itself just completed.
 */
class LoggingAdaptiveDetectionProcessor(
    private val delegate: AdaptiveDetectionProcessor,
    private val logger: DetectionSessionLogger
) : DetectionProcessor {

    override val sampleRate: Int = delegate.sampleRate
    override val blockSize: Int = delegate.blockSize

    override fun process(block: ShortArray): DetectionProcessingResult {
        val result = delegate.process(block)
        delegate.lastDiagnostics?.let { diagnostics ->
            logger.onHop(diagnostics, result.event)
        }
        return result
    }
}
