package hr.sonicpulse.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import hr.sonicpulse.app.R
import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.audio.AudioRecorder
import hr.sonicpulse.app.data.audio.PeakTimeCalculator
import hr.sonicpulse.app.data.location.LocationProvider
import hr.sonicpulse.app.data.location.LocationStartResult
import hr.sonicpulse.app.data.remote.DetectionSubmitter
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.engine.DetectionEngine
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the active monitoring process: audio capture and engine processing run synchronously on
 * AudioRecorder's own capture thread (no separate executor here, per the threading contract) —
 * this service only coordinates lifecycle and publishes state. Location updates run alongside
 * audio capture (foregroundServiceType "microphone|location"), started asynchronously via
 * LocationProvider.start() — AudioRecorder only starts once that reports LocationStartResult.Started.
 * [MonitoringLifecycleCoordinator] guards this asynchronous startup with a generation token, so a
 * location result that arrives after its attempt was invalidated (e.g. by a Stop) can never
 * resurrect state or start audio capture.
 *
 * The audio thread itself never touches LocationProvider except reading [LocationProvider.currentSnapshot]
 * (a cheap volatile-field read, not I/O) at the exact moment a detection is handed off — capturing
 * the classification as it stood then, before handing the rest of the work to this service's own
 * coroutine scope.
 *
 * Submission runs on [serviceScope] after the location snapshot: the audio thread never waits on
 * it, only publishes the local detection and moves on to the next block.
 *
 * Must only be started (via [startIntent]) from a visible user action (e.g. a Start button on
 * the Monitoring screen) — required for while-in-use permissions (RECORD_AUDIO, location) to
 * apply, and the future UI is expected to have already requested and confirmed both permissions
 * before calling `startForegroundService()`. This service still defensively re-checks both
 * itself (via [MonitoringStartupGate]) — Android 14+ validates permissions again when promoting
 * a foreground service, so `ServiceCompat.startForeground()` can fail with a SecurityException
 * even when a permission check moments earlier passed.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject
    lateinit var monitoringStateRepository: MonitoringStateRepository

    @Inject
    lateinit var locationProvider: LocationProvider

    @Inject
    lateinit var detectionSubmitter: DetectionSubmitter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineConfig = EngineConfig()
    private val sessionCoordinator by lazy { MonitoringSessionCoordinator(monitoringStateRepository) }
    private val lifecycleCoordinator = MonitoringLifecycleCoordinator()
    private val refreshCoordinator = MonitoringRefreshCoordinator()

    private var audioRecorder: AudioRecorder? = null
    private var firstBlockInstant: Instant? = null
    private var locationPollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoringIfNeeded()
            ACTION_STOP -> stopMonitoringAndService()
            ACTION_REFRESH_LOCATION -> refreshLocation()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val effect = lifecycleCoordinator.onStopOrDestroy()
        refreshCoordinator.invalidate()
        if (effect is MonitoringLifecycleEffect.StopSession) {
            sessionCoordinator.endSession(effect.wasActive)
            tearDownCaptureAndLocation()
        }
        // Normal teardown only — gives every still-Pending detection
        // a terminal Failed(Cancelled) result before the coroutine scope that would submit it dies.
        // Idempotent: a no-op if nothing is retained and Pending.
        monitoringStateRepository.cancelPendingSubmissions()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoringIfNeeded() {
        val effect = lifecycleCoordinator.onActionStart()
        val generation = (effect as? MonitoringLifecycleEffect.StartLocation)?.generation ?: return

        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            locationPermissionLevel = locationProvider::permissionLevel,
            areLocationServicesEnabled = locationProvider::areLocationServicesEnabled,
            startForeground = ::tryStartForeground
        )
        when (val result = gate.attemptStartup()) {
            is MonitoringStartupResult.Failed -> abortStartup(result.failure)
            MonitoringStartupResult.Proceed -> beginLocationStart(generation)
        }
    }

    private fun beginLocationStart(generation: Long) {
        locationProvider.start { result ->
            serviceScope.launch { handleLocationStartResult(generation, result) }
        }
    }

    private fun handleLocationStartResult(generation: Long, result: LocationStartResult) {
        when (val effect = lifecycleCoordinator.onLocationStartResult(generation, result)) {
            MonitoringLifecycleEffect.StartAudioCapture -> startAudioCapture()
            is MonitoringLifecycleEffect.ReportStartupFailure -> {
                monitoringStateRepository.monitoringStartupFailed(effect.failure)
                stopForegroundCompat()
                stopSelf()
            }
            MonitoringLifecycleEffect.None,
            is MonitoringLifecycleEffect.StopSession,
            is MonitoringLifecycleEffect.StartLocation -> Unit // stale callback or not applicable here; ignore
        }
    }

    /** A synchronous startup-gate failure (permission/location-services/foreground promotion) —
     * nothing async was ever started, but the lifecycle must still return to IDLE. */
    private fun abortStartup(failure: MonitoringStartupFailure) {
        lifecycleCoordinator.onStopOrDestroy()
        monitoringStateRepository.monitoringStartupFailed(failure)
        stopForegroundCompat()
        stopSelf()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Distinguishes a missing/denied permission (SecurityException — could be RECORD_AUDIO or
     * location; MonitoringStartupGate attributes which one) from the OS refusing the promotion
     * for an unrelated reason, e.g. ForegroundServiceStartNotAllowedException (API 31+) — a
     * subclass of IllegalStateException, not SecurityException, and not a permission problem.
     */
    private fun tryStartForeground(): ForegroundStartOutcome {
        createNotificationChannelIfNeeded()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            ForegroundStartOutcome.Started
        } catch (e: SecurityException) {
            // Message intentionally omitted: it may echo permission/package details back into
            // logs. The catch itself already tells us why this can happen (see KDoc above).
            Log.w(TAG, "startForeground() rejected the foreground service promotion")
            ForegroundStartOutcome.PermissionDenied(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground() failed for a non-permission reason")
            ForegroundStartOutcome.Failed(e)
        }
    }

    private fun startAudioCapture() {
        firstBlockInstant = null

        val engine = DetectionEngine(engineConfig)
        val recorder = AudioRecorder(
            getSystemService(AudioManager::class.java),
            engineConfig.sampleRate,
            engineConfig.blockSize
        )
        audioRecorder = recorder

        // MonitoringSessionCoordinator guarantees monitoringStarted() runs before recorder.start()
        // and that a capture failure (even one AudioRecorder reports synchronously, before its
        // own start() call returns) always has the last word on repository state.
        sessionCoordinator.startSession(
            startCapture = { onBlock, onError -> recorder.start(onBlock, onError) },
            onBlock = { block -> handleBlock(engine, block) },
            onCaptureError = { error -> serviceScope.launch { handleCaptureError(error) } }
        )

        // serviceScope uses Dispatchers.Main.immediate, so a synchronous capture failure (recorder
        // calling onError before recorder.start() itself returns) runs handleCaptureError() inline
        // — including tearDownCaptureAndLocation() — before this line, while locationPollingJob is
        // still null (it's assigned below). That teardown can therefore never cancel a job that
        // doesn't exist yet: only start polling if the session is genuinely still ACTIVE here.
        if (lifecycleCoordinator.state != MonitoringLifecycleState.ACTIVE) {
            return
        }

        // The audio thread only reads currentSnapshot at the instant of a detection; this is the
        // separate, continuous feed the Monitoring screen needs for its live status pill and
        // location card, regardless of whether any detection ever occurs.
        locationPollingJob = serviceScope.launch {
            while (isActive) {
                monitoringStateRepository.updateLocationStatus(
                    snapshot = locationProvider.currentSnapshot,
                    permissionLevel = locationProvider.permissionLevel(),
                    servicesEnabled = locationProvider.areLocationServicesEnabled()
                )
                delay(LOCATION_POLL_INTERVAL_MILLIS)
            }
        }
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
            // Captured here, on the audio thread, at the exact moment of handoff — not inside
            // the coroutine below, whose scheduling could otherwise let a newer location update
            // arrive first and misrepresent what was actually known when this detection occurred.
            val locationSnapshot = locationProvider.currentSnapshot
            val sessionDetection = sessionDetectionFor(event.peakDbfs, peakTimeClient, locationSnapshot)
            if (sessionDetection != null) {
                // Published synchronously (MutableStateFlow.update is thread-safe) so the detection
                // is visibly Pending before any submission attempt is even scheduled — insertion and
                // submission are deliberately not both inside the launched coroutine below, so a
                // cancelled/never-started submission coroutine can never leave the detection unlisted.
                monitoringStateRepository.localDetectionOccurred(sessionDetection)
                serviceScope.launch {
                    detectionSubmitter.submit(sessionDetection)
                }
            }
        }
    }

    private fun handleCaptureError(error: AudioCaptureError) {
        // monitoringFailed(error) has already been published by MonitoringSessionCoordinator
        // (synchronously, inside the onError it wraps) — this only does the Service-specific
        // cleanup that must run on this service's own coordinating thread.
        val effect = lifecycleCoordinator.onStopOrDestroy()
        if (effect !is MonitoringLifecycleEffect.StopSession) {
            // Already torn down by an explicit stop that raced ahead of this (possibly stale)
            // capture-error notification; nothing further to do.
            return
        }
        // false, not effect.wasActive: an error already fully describes the terminal state via
        // monitoringFailed() above — a monitoringStopped() call here must not run alongside it.
        sessionCoordinator.endSession(wasActiveBeforeTeardown = false)
        tearDownCaptureAndLocation()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Location-only refresh for the precise-location upgrade flow: never touches audio capture,
     * DetectionEngine, the session, or the foreground notification — only the location subscription
     * is stopped and restarted, since [LocationProvider.start] is itself a no-op while already
     * active and Android does not auto-upgrade an active subscription to precise fixes on its own.
     * [locationPollingJob] is left running throughout and simply observes whatever
     * [LocationProvider.currentSnapshot] becomes once the fresh subscription resolves.
     *
     * Guarded on [MonitoringLifecycleCoordinator.state] being ACTIVE via [refreshCoordinator]: a
     * stale intent reaching an instance where nothing is running must do no monitoring work and
     * stop that instance, not silently no-op and leave it dangling.
     */
    private fun refreshLocation() {
        val effect = refreshCoordinator.onRefreshRequested(lifecycleCoordinator.state)
        val generation = (effect as? MonitoringRefreshEffect.Begin)?.generation
        if (generation == null) {
            stopSelf()
            return
        }
        locationProvider.stop()
        locationProvider.start { result ->
            serviceScope.launch { handleRefreshResult(generation, result) }
        }
    }

    private fun handleRefreshResult(generation: Long, result: LocationStartResult) {
        if (!refreshCoordinator.isCurrent(generation, lifecycleCoordinator.state)) {
            // Superseded by a newer refresh, or monitoring already stopped/destroyed — including
            // the ordinary case where this Cancelled came from our own stop() above.
            return
        }
        when (result) {
            // The existing location-polling job already observes the fresh subscription — clear
            // any error from a previous refresh attempt, since this one succeeded.
            LocationStartResult.Started -> monitoringStateRepository.locationRefreshSucceeded()
            // Reaching this branch at all means the guard above already confirmed we're current
            // and still ACTIVE — our own stop() (superseded/Stop/destroy) would never get here,
            // since that always changes generation and/or lifecycle state first. A Cancelled that
            // DOES reach this point is therefore a genuine failure: the refreshed location request
            // never resolved and no subscription is active — nonfatal, same as the other branches.
            LocationStartResult.Cancelled ->
                monitoringStateRepository.locationRefreshFailed(LocationRefreshFailure.Failed)
            LocationStartResult.PermissionDenied ->
                monitoringStateRepository.locationRefreshFailed(LocationRefreshFailure.PermissionDenied)
            LocationStartResult.LocationServicesDisabled -> {
                monitoringStateRepository.updateLocationStatus(
                    snapshot = locationProvider.currentSnapshot,
                    permissionLevel = locationProvider.permissionLevel(),
                    servicesEnabled = false
                )
                monitoringStateRepository.locationRefreshFailed(LocationRefreshFailure.LocationServicesDisabled)
            }
            is LocationStartResult.Failed ->
                monitoringStateRepository.locationRefreshFailed(LocationRefreshFailure.Failed)
        }
    }

    private fun stopMonitoringAndService() {
        val effect = lifecycleCoordinator.onStopOrDestroy()
        refreshCoordinator.invalidate()
        if (effect is MonitoringLifecycleEffect.StopSession) {
            sessionCoordinator.endSession(effect.wasActive)
            tearDownCaptureAndLocation()
        }
        // Capture/location are stopped above first, so the audio thread cannot publish another
        // detection after this point — only then is it safe to give every still-Pending detection
        // a terminal Failed(Cancelled) result. Outside the StopSession branch (and thus independent
        // of it) and idempotent, so repeated or stale Stop handling stays safe; onDestroy() below
        // still calls this too, as a defensive fallback for teardown paths that don't go through here.
        monitoringStateRepository.cancelPendingSubmissions()
        stopForegroundCompat()
        stopSelf()
    }

    private fun tearDownCaptureAndLocation() {
        locationPollingJob?.cancel()
        locationPollingJob = null
        locationProvider.stop()
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

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_monitoring_active))
            // TODO(design-system): R.mipmap.ic_launcher is a placeholder — must be replaced
            // with a proper monochrome notification small icon before release.
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "hr.sonicpulse.app.action.START_MONITORING"
        private const val ACTION_STOP = "hr.sonicpulse.app.action.STOP_MONITORING"
        private const val ACTION_REFRESH_LOCATION = "hr.sonicpulse.app.action.REFRESH_LOCATION"
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L

        fun startIntent(context: Context): Intent =
            Intent(context, MonitoringService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)

        /** Sent via startService() (never startForegroundService() — monitoring must already be
         * ACTIVE and foreground for this to do anything). */
        fun refreshLocationIntent(context: Context): Intent =
            Intent(context, MonitoringService::class.java).setAction(ACTION_REFRESH_LOCATION)
    }
}
