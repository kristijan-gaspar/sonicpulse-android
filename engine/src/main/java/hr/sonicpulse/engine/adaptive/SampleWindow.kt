package hr.sonicpulse.engine.adaptive

/**
 * Read-only, chronologically ordered view over a fixed number of audio samples.
 *
 * Index 0 is the oldest sample, index `size - 1` is the newest. Implementations back
 * this view with mutable storage they own; the view itself exposes no way to write
 * through to that storage, only to read it. A view returned by
 * [RollingAnalysisWindow.update] is only valid for reads made before the next call
 * to `update` — after that, the same indices refer to the next window.
 */
interface SampleWindow {
    val size: Int

    operator fun get(index: Int): Short
}
