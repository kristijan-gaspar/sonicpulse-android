package hr.sonicpulse.engine.metrics

class BaselineTracker(
    private val alphaDown: Double,
    private val alphaUp: Double
) {
    var value: Double = 0.0
        private set

    private var initialized: Boolean = false

    fun update(dbfs: Double) {
        if (!initialized) {
            value = dbfs
            initialized = true
            return
        }

        val alpha = if (dbfs < value) alphaDown else alphaUp
        value += alpha * (dbfs - value)
    }
}
