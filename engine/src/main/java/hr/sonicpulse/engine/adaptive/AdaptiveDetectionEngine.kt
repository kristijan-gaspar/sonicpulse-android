package hr.sonicpulse.engine.adaptive

import hr.sonicpulse.engine.DetectionEvent
import hr.sonicpulse.engine.metrics.AudioLevelCalculator
import hr.sonicpulse.engine.metrics.ClippingCalculator
import kotlin.math.pow

class AdaptiveDetectionEngine(private val config: AdaptiveEngineConfig = AdaptiveEngineConfig()) {

    private val analysisWindow = RollingAnalysisWindow(config)
    private val backgroundEstimator = AdaptiveBackgroundEstimator(config)
    private val variationHistory = RobustVariationThresholdHistory(config)
    private val thresholdEvaluator = AdaptiveThresholdEvaluator()
    private val robustThresholdCalculator =
        AdaptiveRobustThresholdCalculator(config, backgroundEstimator, variationHistory, thresholdEvaluator)
    private val stateMachine = AdaptiveDetectionStateMachine(config)

    // Power-domain rise: currentPower > mfa * 10^(minRelativePowerRiseDb / 10), NOT the
    // amplitude-domain 20*log10 form - power ratios use a base-10 divisor of 10, not 20.
    private val relativePowerRiseFactor = 10.0.pow(config.minRelativePowerRiseDb / 10.0)

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

        val window = analysisWindow.update(hop)
        if (window == null) {
            val stateNow = stateMachine.state
            lastDiagnostics = AdaptiveHopDiagnostics(
                hopIndex = hopIndex,
                analysisReady = false,
                dbfs = lastDbfs,
                power = null,
                crestDb = null,
                crestWindowDb = null,
                clipRatio = null,
                mfa = null,
                variation = null,
                th = null,
                threshold = null,
                isBootstrapping = null,
                energyExceeded = null,
                relativePowerExceeded = null,
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
        val crestWindowDb = CrestFactorCalculator.calculate(window)

        val evaluation = robustThresholdCalculator.evaluate(currentPower)
        val energyExceeded = evaluation?.let {
            !it.isBootstrapping && it.exceedsThreshold
        } ?: false
        val relativePowerExceeded = evaluation?.let {
            !it.isBootstrapping && currentPower > it.mfa * relativePowerRiseFactor
        } ?: false
        val impulsive = (crestDb != null && crestDb > config.crestMinDb) || clipRatio > config.clipRatioMin
        val trigger = energyExceeded && relativePowerExceeded && impulsive

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
            crestWindowDb = crestWindowDb,
            clipRatio = clipRatio,
            mfa = evaluation?.mfa,
            variation = evaluation?.variation,
            th = evaluation?.th,
            threshold = evaluation?.threshold,
            isBootstrapping = evaluation?.isBootstrapping,
            energyExceeded = energyExceeded,
            relativePowerExceeded = relativePowerExceeded,
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
