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
import hr.sonicpulse.app.MainActivity
import hr.sonicpulse.app.R
import hr.sonicpulse.app.data.audio.AudioCaptureError
import hr.sonicpulse.app.data.audio.AudioRecorder
import hr.sonicpulse.app.data.audio.PeakTimeCalculator
import hr.sonicpulse.app.data.location.LocationProvider
import hr.sonicpulse.app.data.location.LocationStartResult
import hr.sonicpulse.app.data.location.LocationPermissionLevel
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.data.remote.DetectionSubmitter
import hr.sonicpulse.app.observability.DetectionSessionLogger
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.engine.DetectionEngine
import hr.sonicpulse.engine.EngineConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the active monitoring process: audio capture and engine processing run synchronously on
 * AudioRecorder's own capture thread (no separate executor here, per the threading contract) —
 * this service only coordinates lifecycle and publishes state. Location updates run alongside
 * audio capture (foregroundServiceType "microphone|location"), started asynchronously via
 * LocationProvider.start() — AudioRecorder only starts once that reports LocationStartResult.Started.
 *
 * Session ownership: every Start creates its own [MonitoringSession] (audio recorder, submission
 * tracker, first-block timing, processing-failure flag, location-polling job), owned exclusively
 * by [sessionRunner] — see [MonitoringSessionRunner]'s KDoc for exactly how it prevents an old,
 * still-running teardown from ever reading or mutating a newer session's resources, and how a
 * Start arriving mid-teardown is queued (never raced) as a single pending restart rather than
 * starting a second, overlapping session. Every callback that could touch shared/global state
 * (the repository, the diagnostic logger, a submission job) carries the exact [MonitoringSession]
 * it belongs to and checks it against [MonitoringSessionRunner.currentSession] first — a callback
 * whose session is no longer current is a stale leftover from a torn-down (or being torn down)
 * session and is silently ignored.
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

    @Inject
    lateinit var detectionSessionLogger: DetectionSessionLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineConfig = EngineConfig()
    private val sessionCoordinator by lazy { MonitoringSessionCoordinator(monitoringStateRepository) }
    private val lifecycleCoordinator = MonitoringLifecycleCoordinator()
    private val refreshCoordinator = MonitoringRefreshCoordinator()

    private class MonitoringSession(val generation: Long) {
        var recorder: AudioRecorder? = null
        var submissionTracker: SubmissionJobTracker? = null
        var firstBlockInstant: Instant? = null
        val processingFailureReported = AtomicBoolean(false)
        var locationPollingJob: Job? = null
    }

    private val sessionRunner = MonitoringSessionRunner(
        lifecycleCoordinator = lifecycleCoordinator,
        scope = serviceScope,
        sessions = object : MonitoringSessionRunner.Sessions<MonitoringSession> {
            override fun create(generation: Long): MonitoringSession = MonitoringSession(generation)

            override suspend fun tearDown(session: MonitoringSession?, wasActive: Boolean) {
                tearDownSession(session, wasActive)
            }

            override fun onIdle() {
                stopSelfResult(latestStartId)
            }

            override fun onRestart(generation: Long, session: MonitoringSession) {
                continueStartup(session)
            }

            override fun onTeardownFailure(throwable: Throwable) {

                Log.e(TAG, "Monitoring session teardown failed", throwable)
                runCatching { stopForegroundCompat() }
                runCatching { monitoringStateRepository.cancelPendingSubmissions() }
            }
        }
    )

    /** The most recently delivered `startId`, updated unconditionally on every [onStartCommand]
     * call regardless of action or lifecycle state — read fresh at the exact moment
     * [stopSelfResult] is actually called, never threaded through as a parameter captured
     * earlier. This is what lets a duplicate/ignored Start (still the *same* generation) extend a
     * pending stop's authority, while a Start that genuinely begins a new session is instead
     * handled by [sessionRunner]'s queued-restart mechanism — stopSelfResult is simply never
     * called in that case (see [MonitoringSessionRunner.Sessions.onRestart]). */
    private var latestStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> startMonitoringIfNeeded()
            ACTION_STOP -> requestStop()
            ACTION_REFRESH_LOCATION -> refreshLocation()
            else -> stopSelfResult(latestStartId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val job = sessionRunner.stop()
        job.invokeOnCompletion { serviceScope.cancel() }
        super.onDestroy()
    }

    private fun startMonitoringIfNeeded() {
        val session = sessionRunner.start() ?: return
        continueStartup(session)
    }

    private fun continueStartup(session: MonitoringSession) {
        if (sessionRunner.currentSession !== session) {
            return
        }

        val gate = MonitoringStartupGate(
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            locationPermissionLevel = locationProvider::permissionLevel,
            areLocationServicesEnabled = locationProvider::areLocationServicesEnabled,
            startForeground = ::tryStartForeground,
            enableLocationForegroundType = ::tryEnableLocationForegroundType
        )

        when (val result = gate.attemptStartup()) {
            is MonitoringStartupResult.Failed -> {
                abortStartup(session, result.failure)
            }

            MonitoringStartupResult.Proceed -> {
                beginLocationStart(session.generation)
            }
        }
    }

    /** A synchronous startup-gate failure (permission/location-services/foreground promotion) —
     * nothing async was ever started, so there is nothing to tear down: goes straight back to
     * IDLE via [MonitoringLifecycleCoordinator.onSynchronousStartupAbort], never through
     * [sessionRunner]'s async teardown-then-resolve machinery. */
    private fun abortStartup(
        session: MonitoringSession,
        failure: MonitoringStartupFailure
    ) {
        if (sessionRunner.currentSession !== session) {
            return
        }

        lifecycleCoordinator.onSynchronousStartupAbort()
        sessionRunner.discardStarting(session)
        monitoringStateRepository.monitoringStartupFailed(failure)
        stopForegroundCompat()
        stopSelfResult(latestStartId)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            ForegroundStartOutcome.Started
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground() rejected the foreground service promotion")
            ForegroundStartOutcome.PermissionDenied(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground() failed for a non-permission reason")
            ForegroundStartOutcome.Failed(e)
        }
    }

    private fun tryEnableLocationForegroundType(): ForegroundStartOutcome {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            ForegroundStartOutcome.Started
        } catch (e: SecurityException) {
            Log.w(TAG, "Location foreground service type promotion was rejected")
            ForegroundStartOutcome.PermissionDenied(e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Location foreground service type promotion failed")
            ForegroundStartOutcome.Failed(e)
        }
    }

    private fun beginLocationStart(generation: Long) {
        locationProvider.start { result ->
            serviceScope.launch { handleLocationStartResult(generation, result) }
        }
    }

    private fun handleLocationStartResult(
        generation: Long,
        result: LocationStartResult
    ) {
        val session = sessionRunner.currentSession
        if (session == null || session.generation != generation) {
            return
        }

        when (val effect = lifecycleCoordinator.onLocationStartResult(generation, result)) {
            MonitoringLifecycleEffect.StartAudioCapture -> {
                startAudioCapture(generation)
            }

            is MonitoringLifecycleEffect.ReportStartupFailure -> {
                monitoringStateRepository.monitoringStartupFailed(effect.failure)
                stopForegroundCompat()
                sessionRunner.discardStarting(session)
                stopSelfResult(latestStartId)
            }

            MonitoringLifecycleEffect.None,
            is MonitoringLifecycleEffect.StopSession,
            is MonitoringLifecycleEffect.StartLocation -> Unit
        }
    }

    private fun startAudioCapture(generation: Long) {
        val session = sessionRunner.currentSession
        if (session == null || session.generation != generation) {
            // Should not happen — onLocationStartResult's own generation check already guards
            // this — but never proceed against a session we can no longer positively identify.
            return
        }


        detectionSessionLogger.startSession(engineConfig)

        val engine = DetectionEngine(engineConfig)
        val recorder = AudioRecorder(
            getSystemService(AudioManager::class.java),
            engineConfig.sampleRate,
            engineConfig.blockSize
        )
        session.recorder = recorder
        session.submissionTracker = SubmissionJobTracker()


        sessionCoordinator.startSession(
            startCapture = { onBlock, onError ->
                recorder.start(onBlock, onError)
            },
            onBlock = { block ->
                handleBlockSafely(session, engine, block)
            },
            onCaptureError = { error ->
                serviceScope.launch {
                    handleCaptureError(session, error)
                }
            }
        )

        if (lifecycleCoordinator.state != MonitoringLifecycleState.ACTIVE) {
            return
        }

        // The audio thread only reads currentSnapshot at the instant of a detection; this is the
        // separate, continuous feed the Monitoring screen needs for its live status pill and
        // location card, regardless of whether any detection ever occurs.
        session.locationPollingJob = serviceScope.launch {
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

    /**
     * The boundary between AudioRecorder's dedicated capture thread and this service's own
     * block-processing logic. AudioRecorder must only ever see genuine capture failures
     * (init/permission/read/lifecycle) — never a bug in [DetectionEngine.process], the session
     * logger, or detection construction, which would otherwise escape as
     * [AudioCaptureError.Unexpected] and be misreported as a microphone hardware failure. Those
     * are caught here instead and reported through the distinct processing-failure path.
     *
     * Runs synchronously on the capture thread, so this itself must stay synchronous; the
     * teardown it triggers is dispatched onto [serviceScope]. [CancellationException] is never
     * caught here — coroutine cancellation must keep propagating normally.
     *
     * [session] is the exact session [startAudioCapture] created this callback for — checked
     * against [MonitoringSessionRunner.currentSession] before anything here touches shared state
     * (the repository, the diagnostic logger, a submission job), so a block that arrives after
     * this session has already stopped being current (a stale callback from a torn-down session,
     * however that happened) is silently dropped instead of corrupting a newer session's view.
     */
    private fun handleBlockSafely(session: MonitoringSession, engine: DetectionEngine, block: ShortArray) {
        if (sessionRunner.currentSession !== session) {
            return
        }
        if (session.processingFailureReported.get()) {
            // Already failing and tearing down asynchronously — do not feed a possibly corrupt
            // engine/logger any further blocks while that teardown is still pending.
            return
        }
        try {
            handleBlock(session, engine, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (session.processingFailureReported.compareAndSet(false, true)) {
                Log.e(TAG, "Block processing failed; stopping the session", e)
                serviceScope.launch { handleProcessingError(session) }
            }
        }
    }

    private fun handleBlock(session: MonitoringSession, engine: DetectionEngine, block: ShortArray) {
        val startInstant = session.firstBlockInstant ?: PeakTimeCalculator.blockZeroInstant(
            Instant.now(),
            engineConfig.sampleRate,
            engineConfig.blockSize
        ).also { session.firstBlockInstant = it }

        val event = engine.process(block)
        val completion = engine.lastCandidateCompletion
        val metrics = engine.lastBlockMetrics


        val finalizedCandidate = completion?.let {
            finalizedCandidateFor(it, startInstant, engineConfig.sampleRate, engineConfig.blockSize)
        }

        if (metrics != null) {
            monitoringStateRepository.publishMetrics(metrics)
            detectionSessionLogger.onBlock(metrics, finalizedCandidate)
        }

        if (event != null) {

            val locationSnapshot = currentSubmittableLocationSnapshot()
            if (locationSnapshot != null) {
                val sessionDetection = sessionDetectionFor(
                    event.peakDbfs,
                    finalizedCandidate!!.peakTimeClient,
                    locationSnapshot
                )

                if (sessionDetection != null) {
                    monitoringStateRepository.localDetectionOccurred(sessionDetection)
                    submitTracked(session, sessionDetection)
                }
            }
        }
    }

    /** Creates the submission job lazily and only ever starts it if the session's tracker
     * actually accepts the registration — a job rejected because shutdown has already begun for
     * this session is cancelled outright, so no HTTP request is ever made for a detection nothing
     * is waiting to resolve. */
    private fun submitTracked(session: MonitoringSession, sessionDetection: hr.sonicpulse.app.domain.model.SessionDetection) {
        val tracker = session.submissionTracker
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            detectionSubmitter.submit(sessionDetection)
        }
        if (tracker != null && tracker.register(sessionDetection.localEventId, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    /**
     * MonitoringSessionCoordinator only ever forwards a capture error here — it never decides
     * ownership or touches the repository itself. The identity check and the monitoringFailed()
     * publish must happen together, in that order, on this single dispatcher call: a Stop that
     * lands between "the error was produced on the capture thread" and "this callback actually
     * runs" already invalidated [session] (cleared sessionRunner.currentSession) by the time this
     * runs, so the check below makes the whole error a no-op — never a repository mutation that
     * could overwrite the normal stopped state with a stale captureError.
     */
    private fun handleCaptureError(session: MonitoringSession, error: AudioCaptureError) {
        if (sessionRunner.currentSession !== session) {
            // Already superseded by a newer session (or a Stop that raced ahead of this) —
            // nothing further to do, and unconditionally stopping now could kill a session this
            // failure has nothing to do with.
            return
        }
        monitoringStateRepository.monitoringFailed(error)
        sessionRunner.stop()
    }

    /** Same shape as [handleCaptureError], for a processing (not capture) failure detected by
     * [handleBlockSafely] — see its KDoc for why the two are kept distinct. */
    private fun handleProcessingError(session: MonitoringSession) {
        if (sessionRunner.currentSession !== session) {
            return
        }
        monitoringStateRepository.monitoringProcessingFailed()
        sessionRunner.stop()
    }

    /**
     * Location-only refresh for the precise-location upgrade flow: never touches audio capture,
     * DetectionEngine, the session, or the foreground notification — only the location subscription
     * is stopped and restarted, since [LocationProvider.start] is itself a no-op while already
     * active and Android does not auto-upgrade an active subscription to precise fixes on its own.
     * The current session's location-polling job is left running throughout and simply observes
     * whatever [LocationProvider.currentSnapshot] becomes once the fresh subscription resolves.
     *
     * Guarded on [MonitoringLifecycleCoordinator.state] being ACTIVE via [refreshCoordinator]: a
     * stale intent reaching an instance where nothing is running must do no monitoring work and
     * stop that instance, not silently no-op and leave it dangling.
     */
    private fun refreshLocation() {
        val effect = refreshCoordinator.onRefreshRequested(lifecycleCoordinator.state)
        val generation = (effect as? MonitoringRefreshEffect.Begin)?.generation
        if (generation == null) {
            // stopSelfResult, not stopSelf: this stale-refresh instance must only be stopped if
            // no newer command has been delivered since this one.
            stopSelfResult(latestStartId)
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

    private fun requestStop() {
        refreshCoordinator.invalidate()
        sessionRunner.stop()
    }

    /**
     * The one owned teardown sequence, run for whichever [session] [sessionRunner] captured the
     * instant it began (or `null` if there was nothing to tear down) — never re-reads
     * `sessionRunner.currentSession`/any other service-level mutable field once started, so it
     * can never observe (let alone mutate) whatever session, if any, replaces this one while this
     * suspends. Sequenced exactly as required: audio/location stop, bounded wait for the audio
     * worker, diagnostic logger finalize, submission drain, remaining-Pending resolution,
     * notification removal — in that order, every step fully finished before the next begins.
     */
    private suspend fun tearDownSession(session: MonitoringSession?, wasActive: Boolean) {
        if (session != null) {
            sessionCoordinator.endSession(wasActive)
            session.locationPollingJob?.cancel()
            locationProvider.stop()
            // AudioRecorder.close() waits, with a bounded timeout, for the capture thread to
            // drain — never indefinitely, but still a genuine wait — so it must never run on the
            // main thread; withContext moves just that call onto a background dispatcher.
            withContext(Dispatchers.IO) {
                session.recorder?.close()
            }
            // MonitoringSessionRunner guarantees no newer session's startSession() can run until
            // this suspend function returns, so this can never finalize a session it doesn't own.
            detectionSessionLogger.finishSession()
        }
        val tracker = session?.submissionTracker
        SubmissionDrain.await(
            tracker?.closeAndSnapshot() ?: emptyList(),
            SUBMISSION_DRAIN_TIMEOUT_MILLIS,
            SUBMISSION_DRAIN_GRACE_MILLIS
        )
        // Gives every still-Pending detection a terminal Failed(Cancelled) result. Idempotent: a
        // no-op if nothing is retained and Pending — safe even when session was null.
        monitoringStateRepository.cancelPendingSubmissions()
        stopForegroundCompat()
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
        // FLAG_ACTIVITY_NEW_TASK: required whenever starting an Activity from a non-Activity
        // Context (a notification tap) — combined with MainActivity's singleTask launch mode
        // (manifest), this brings the existing instance to front instead of creating a duplicate
        // one on top of it, so tapping the notification never produces a second back stack.
        val contentIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_monitoring_active))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun currentSubmittableLocationSnapshot(): LocationSnapshot? {
        if (locationProvider.permissionLevel() == LocationPermissionLevel.NONE) {
            return null
        }

        if (!locationProvider.areLocationServicesEnabled()) {
            return null
        }

        return locationProvider.currentSnapshot
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "hr.sonicpulse.app.action.START_MONITORING"
        private const val ACTION_STOP = "hr.sonicpulse.app.action.STOP_MONITORING"
        private const val ACTION_REFRESH_LOCATION = "hr.sonicpulse.app.action.REFRESH_LOCATION"
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L

        /** Bounds the submission drain's wait for in-flight submissions during shutdown — long
         * enough for a normal HTTP round trip, short enough that Stop still feels immediate. */
        private const val SUBMISSION_DRAIN_TIMEOUT_MILLIS = 3_000L

        /** Second, short bound on top of [SUBMISSION_DRAIN_TIMEOUT_MILLIS]: how long to wait for
         * a cancelled-but-not-yet-finished submission job's cancellation to actually take effect
         * before giving up on it regardless — see [SubmissionDrain]. */
        private const val SUBMISSION_DRAIN_GRACE_MILLIS = 500L

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
