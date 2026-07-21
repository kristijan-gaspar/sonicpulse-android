package hr.sonicpulse.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.R
import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.audio.AudioRecorder
import hr.sonicpulse.app.data.audio.PeakTimeCalculator
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.engine.DetectionEngine
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the active monitoring process (§2.6/§2.11): audio capture and engine processing run
 * synchronously on AudioRecorder's own capture thread (no separate executor here, per the
 * threading contract) — this service only coordinates lifecycle and publishes state.
 *
 * Microphone-only for this branch: no location, no backend submission.
 *
 * Must only be started (via [startIntent]) from a visible user action (e.g. a Start button on
 * the Monitoring screen) — required for while-in-use permissions (RECORD_AUDIO) to apply, and
 * the future UI is expected to have already requested and confirmed RECORD_AUDIO before calling
 * `startForegroundService()`. This service still defensively re-checks RECORD_AUDIO itself
 * (via [MonitoringStartupGate]) — Android 14+ validates the permission again when promoting a
 * microphone-typed foreground service, so `ServiceCompat.startForeground()` can fail with a
 * SecurityException even when a permission check moments earlier passed.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject
    lateinit var monitoringStateRepository: MonitoringStateRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineConfig = EngineConfig()

    private var audioRecorder: AudioRecorder? = null
    private var firstBlockInstant: Instant? = null
    private var isMonitoringActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoringAndService()
        } else {
            startMonitoringIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMonitoringInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoringIfNeeded() {
        if (isMonitoringActive) {
            return
        }

        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            startForeground = ::tryStartForeground
        )
        when (gate.attemptStartup()) {
            MonitoringStartupResult.PermissionDenied -> {
                monitoringStateRepository.monitoringFailed(AudioCaptureError.PermissionDenied)
                // Safe even if startForeground() never succeeded (stopForeground() on a
                // service that isn't in the foreground state is a documented no-op).
                stopForegroundCompat()
                stopSelf()
            }
            MonitoringStartupResult.Proceed -> startAudioCapture()
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns false (instead of throwing) if the OS rejects the microphone FGS promotion. */
    private fun tryStartForeground(): Boolean {
        createNotificationChannelIfNeeded()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun startAudioCapture() {
        isMonitoringActive = true
        firstBlockInstant = null

        val engine = DetectionEngine(engineConfig)
        val recorder = AudioRecorder(
            getSystemService(AudioManager::class.java),
            engineConfig.sampleRate,
            engineConfig.blockSize
        )
        audioRecorder = recorder

        recorder.start(
            onBlock = { block -> handleBlock(engine, block) },
            onError = { error -> serviceScope.launch { handleCaptureError(error) } }
        )

        monitoringStateRepository.monitoringStarted()
    }

    private fun handleBlock(engine: DetectionEngine, block: ShortArray) {
        val startInstant = firstBlockInstant ?: Instant.now().also { firstBlockInstant = it }

        val event = engine.process(block)
        engine.lastBlockMetrics?.let { monitoringStateRepository.publishMetrics(it) }

        if (event != null) {
            val peakTimeClient = PeakTimeCalculator.calculate(
                startInstant,
                event.peakBlockIndex,
                engineConfig.sampleRate,
                engineConfig.blockSize
            )
            monitoringStateRepository.localDetectionOccurred(
                SessionDetection(
                    localEventId = UUID.randomUUID(),
                    peakDbfs = event.peakDbfs,
                    peakTimeClient = peakTimeClient
                )
            )
        }
    }

    private fun handleCaptureError(error: AudioCaptureError) {
        stopMonitoringInternal()
        monitoringStateRepository.monitoringFailed(error)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopMonitoringAndService() {
        stopMonitoringInternal()
        monitoringStateRepository.monitoringStopped()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopMonitoringInternal() {
        isMonitoringActive = false
        audioRecorder?.close()
        audioRecorder = null
    }

    private fun createNotificationChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.monitoring_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            // TODO(design-system): R.mipmap.ic_launcher is a placeholder — must be replaced
            // with a proper monochrome notification small icon before release.
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "hr.sonicpulse.app.action.STOP_MONITORING"

        fun startIntent(context: Context): Intent =
            Intent(context, MonitoringService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
    }
}
