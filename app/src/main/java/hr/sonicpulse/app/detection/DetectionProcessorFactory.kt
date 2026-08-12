package hr.sonicpulse.app.detection

/**
 * Creates a fresh [DetectionProcessor] — and therefore a fresh, stateless-from-the-caller's-
 * perspective engine instance — for each monitoring session. Implementations must never
 * hand out a [DetectionProcessor] that carries state left over from a previous session.
 */
interface DetectionProcessorFactory {
    fun create(): DetectionProcessor
}
