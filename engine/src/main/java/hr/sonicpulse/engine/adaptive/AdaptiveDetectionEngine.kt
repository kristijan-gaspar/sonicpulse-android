package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.metrics.AudioLevelCalculator
import hr.sonicpulse.engine.metrics.ClippingCalculator

class AdaptiveDetectionEngine(private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()) {

    private val analysisWindow = RollingAnalysisWindow(config)
    private val backgroundEstimator = AdaptiveBackgroundEstimator(config)
    private val variationHistory = RobustVariationThresholdHistory(config)
    private val thresholdEvaluator = AdaptiveThresholdEvaluator(config.thresholdStdMultiplier)
    private val robustThresholdCalculator =
        AdaptiveRobustThresholdCalculator(backgroundEstimator, variationHistory, thresholdEvaluator)
    private val stateMachine = AdaptiveDetectionStateMachine(config)

    private var processedHopIndex = 0L

    val state: AdaptiveDetectionState get() = stateMachine.state

    var lastDbfs: Double = -120.0
        private set

    var lastDiagnostics: AdaptiveHopDiagnostics? = null
        private set

    fun process(hop: ShortArray): DetectionEvent? {
        require(hop.size == config.hopSize) {
            "hop must have exactly ${config.hopSize} samples, was ${hop.size}."
        }

        val hopIndex = processedHopIndex
        processedHopIndex++

        lastDbfs = AudioLevelCalculator.calculate(hop).dbfs

        // Still filling the 4096-sample analysis window: no power yet, nothing to decide.
        val window = analysisWindow.update(hop)
        if (window == null) {
            val stateNow = stateMachine.state
            lastDiagnostics = AdaptiveHopDiagnostics(
                hopIndex = hopIndex,
                analysisReady = false,
                dbfs = lastDbfs,
                power = null,
                crestDb = null,
                clipRatio = null,
                mfa = null,
                variation = null,
                th = null,
                threshold = null,
                isBootstrapping = null,
                energyExceeded = null,
                impulsive = null,
                trigger = null,
                stateBefore = stateNow,
                stateAfter = stateNow
            )
            return null
        }

        val currentPower = PowerCalculator.calculate(window)
        val currentDbfs = lastDbfs
        val clipRatio = ClippingCalculator.calculateClipRatio(hop, config.clipLevel)
        val crestDb = CrestFactorCalculator.calculate(hop)

        val evaluation = robustThresholdCalculator.evaluate(currentPower)
        val energyExceeded = evaluation?.exceedsThreshold ?: false
        val impulsive = (crestDb != null && crestDb > config.crestMinDb) || clipRatio > config.clipRatioMin
        val trigger = energyExceeded && impulsive

        val stateAtEntry = stateMachine.state
        val event = stateMachine.process(
            trigger = trigger,
            currentPower = currentPower,
            currentDbfs = currentDbfs,
            blockIndex = hopIndex,
            adaptiveThreshold = evaluation?.threshold ?: 0.0
        )
        val stateAfterProcess = stateMachine.state

        backgroundEstimator.addObservation(currentPower)
        evaluation?.let { variationHistory.addVariation(it.variation) }

        lastDiagnostics = AdaptiveHopDiagnostics(
            hopIndex = hopIndex,
            analysisReady = true,
            dbfs = currentDbfs,
            power = currentPower,
            crestDb = crestDb,
            clipRatio = clipRatio,
            mfa = evaluation?.mfa,
            variation = evaluation?.variation,
            th = evaluation?.th,
            threshold = evaluation?.threshold,
            isBootstrapping = evaluation?.isBootstrapping,
            energyExceeded = energyExceeded,
            impulsive = impulsive,
            trigger = trigger,
            stateBefore = stateAtEntry,
            stateAfter = stateAfterProcess
        )

        return event
    }

    fun reset() {
        analysisWindow.reset()
        backgroundEstimator.reset()
        variationHistory.reset()
        stateMachine.reset()
        processedHopIndex = 0L
        lastDbfs = -120.0
        lastDiagnostics = null
    }
}
