package hr.sonicpulse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the async half of [MonitoringLifecycleCoordinator]'s state machine: starting a session,
 * and tearing the current one down (queuing at most one restart if a Start arrives mid-teardown,
 * per [MonitoringLifecycleCoordinator.onActionStart]'s STOPPING behavior). Android/AudioRecorder/
 * SubmissionJobTracker-free — driven entirely by a caller-supplied [sessions] factory/step
 * interface — so the exact Start-A / Stop-A (async) / Start-B / Stop-A-finishes race, and the
 * per-session ownership invariants around it, can be exercised with real coroutines in a plain
 * JVM test (see `MonitoringSessionRunnerTest`), without Robolectric.
 *
 * Ownership is structural, not just a runtime check: [currentSession] is the *only* mutable
 * reference to "whichever session is starting or active" — [stop] captures the exact session
 * instance it owns into a local value the instant teardown begins (before any suspension) and
 * clears [currentSession] in that same synchronous step, so a newer session (if one is queued and
 * later begins) always gets its own distinct [S] instance and can never be reached, let alone
 * mutated, by an old, still-running [Sessions.tearDown] call.
 */
class MonitoringSessionRunner<S>(
    private val lifecycleCoordinator: MonitoringLifecycleCoordinator,
    private val scope: CoroutineScope,
    private val sessions: Sessions<S>
) {

    interface Sessions<S> {
        /** Creates a new session object for [generation] — called synchronously, the instant a
         * session is accepted, whether from a fresh [start] or a queued restart. */
        fun create(generation: Long): S

        /**
         * Tears [session] down — audio/location stop, logger finalize, submission drain,
         * pending-detection resolution, notification removal, whatever the caller needs. Called
         * exactly once per [stop] invocation that actually owns a teardown (never for a call that
         * merely reused an already-running one). [session] is null when there was nothing to tear
         * down (state was already IDLE); implementations must still perform whatever
         * session-independent cleanup they need in that case (mirrors the previous design's
         * "idempotent regardless of whether a session existed" steps).
         */
        suspend fun tearDown(session: S?, wasActive: Boolean)

        /** Called once teardown finishes with nothing queued to restart — including when there
         * was nothing to tear down in the first place. */
        fun onIdle()

        /** Called once a queued restart is ready to actually begin, for the fresh [generation]
         * [MonitoringLifecycleCoordinator.onTeardownComplete] just minted — mirrors what a fresh
         * [start] does. [session] is already the new session ([currentSession] has already been
         * updated to it before this runs). */
        fun onRestart(generation: Long, session: S)
    }

    /** Whichever session is currently starting or active — null while IDLE or STOPPING. The
     * single field a stale callback should compare itself against (by identity or by reading
     * [MonitoringSessionRunner.currentSession] at the point it would touch shared state). */
    @Volatile
    var currentSession: S? = null
        private set

    private var teardownJob: Job? = null

    /** Fresh Start. Returns the new session if one was actually created (state was IDLE), or null
     * if the command was ignored (already STARTING/ACTIVE) or only queued as a pending restart
     * (nothing to return yet — [Sessions.onRestart] supplies it once the current teardown, if
     * any, finishes). */
    fun start(): S? {
        val effect = lifecycleCoordinator.onActionStart()
        val generation = (effect as? MonitoringLifecycleEffect.StartLocation)?.generation ?: return null
        val session = sessions.create(generation)
        currentSession = session
        return session
    }

    /**
     * Stop (or any other trigger meaning "tear down whatever is running now"): idempotent — a
     * call while a teardown is already running reuses that job rather than starting a second,
     * overlapping one. Never blocks the caller: the actual work happens in the returned [Job],
     * launched on [scope].
     */
    fun stop(): Job {

        val effect = lifecycleCoordinator.onStopOrDestroy()

        teardownJob?.let { existing ->
            if (existing.isActive) {
                return existing
            }
        }

        val stopEffect = effect as? MonitoringLifecycleEffect.StopSession
        val sessionToTearDown = stopEffect?.let { currentSession }
        val wasActive = stopEffect?.wasActive ?: false

        currentSession = null

        val job = scope.launch {
            sessions.tearDown(
                session = sessionToTearDown,
                wasActive = wasActive
            )

            when (val resolved = lifecycleCoordinator.onTeardownComplete()) {
                is MonitoringLifecycleEffect.StartLocation -> {
                    val newSession = sessions.create(resolved.generation)
                    currentSession = newSession

                    sessions.onRestart(
                        generation = resolved.generation,
                        session = newSession
                    )
                }

                else -> sessions.onIdle()
            }
        }

        teardownJob = job

        job.invokeOnCompletion {
            if (teardownJob === job) {
                teardownJob = null
            }
        }

        return job
    }

    /** For a synchronous startup failure — nothing async was ever begun for the session [start]
     * just returned, so there is nothing to tear down; this simply forgets it, moving the
     * coordinator itself back to IDLE is the caller's responsibility (see
     * [MonitoringLifecycleCoordinator.onSynchronousStartupAbort]). Must only be called
     * synchronously, immediately after a [start] that returned non-null but then failed before
     * anything async began. */
    fun discardStarting(expectedSession: S) {
        if (currentSession === expectedSession) {
            currentSession = null
        }
    }
}
