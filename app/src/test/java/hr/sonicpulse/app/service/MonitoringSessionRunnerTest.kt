package hr.sonicpulse.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringSessionRunnerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSession(val generation: Long) {
        var tornDown = false
    }

    /** Records every step in the order it actually ran, and — via [tearDownGate] — lets a test
     * pause a specific [tearDown] call mid-flight to simulate a Start arriving while a real
     * teardown (audio close, logger finalize, submission drain...) is still running. */
    private class FakeSessions(
        private val tearDownGate: CompletableDeferred<Unit>? = null
    ) : MonitoringSessionRunner.Sessions<FakeSession> {
        val events = mutableListOf<String>()
        val createdGenerations = mutableListOf<Long>()
        var idleCount = 0
        var restartCount = 0
        val tornDownSessions = mutableListOf<FakeSession?>()

        override fun create(generation: Long): FakeSession {
            createdGenerations += generation
            events += "create($generation)"
            return FakeSession(generation)
        }

        override suspend fun tearDown(session: FakeSession?, wasActive: Boolean) {
            events += "tearDown(${session?.generation}, wasActive=$wasActive)"
            tearDownGate?.await()
            tornDownSessions += session
            session?.tornDown = true
        }

        override fun onIdle() {
            idleCount++
            events += "onIdle"
        }

        override fun onRestart(generation: Long, session: FakeSession) {
            restartCount++
            events += "onRestart($generation)"
        }
    }

    @Test
    fun `start creates a session and it becomes currentSession`() {
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), TestScopeStub, FakeSessions())

        val session = runner.start()

        assertNotNull(session)
        assertSame(session, runner.currentSession)
    }

    @Test
    fun `a duplicate start while already starting is ignored`() {
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), TestScopeStub, FakeSessions())
        val first = runner.start()

        val second = runner.start()

        assertNull(second)
        assertSame(first, runner.currentSession)
    }

    // --- Test 1: Start A, Stop A begins, Start B arrives, B starts exactly once after A finishes ---

    @Test
    fun `Start A, Stop A begins, Start B arrives, B starts exactly once after A teardown completes`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fakeSessions = FakeSessions(tearDownGate = gate)
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), this, fakeSessions)

        val sessionA = runner.start()
        requireNotNull(sessionA)

        val teardownJob = runner.stop()
        testScheduler.runCurrent() // let A's tearDown() begin and suspend on the gate

        assertEquals(1, fakeSessions.createdGenerations.size)
        val startBWhileTearingDown = runner.start()
        assertNull("B must not start while A's teardown is still running", startBWhileTearingDown)
        assertEquals(
            "no new session may be created while A's teardown is still in flight",
            1,
            fakeSessions.createdGenerations.size
        )

        gate.complete(Unit)
        teardownJob.join()

        assertEquals(0, fakeSessions.idleCount)
        assertEquals(1, fakeSessions.restartCount)
        assertEquals(2, fakeSessions.createdGenerations.size)
        assertTrue(
            "B's generation must be newer than A's",
            fakeSessions.createdGenerations[1] > fakeSessions.createdGenerations[0]
        )
        val sessionB = runner.currentSession
        assertNotNull(sessionB)
        assertEquals(fakeSessions.createdGenerations[1], sessionB!!.generation)
        assertFalse("B must never be torn down by A's teardown", sessionB.tornDown)
    }

    @Test
    fun `old teardown A cannot mutate B's session object`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fakeSessions = FakeSessions(tearDownGate = gate)
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), this, fakeSessions)
        runner.start()
        val teardownJob = runner.stop()
        testScheduler.runCurrent()
        runner.start() // queues B
        gate.complete(Unit)
        teardownJob.join()

        val sessionB = runner.currentSession
        assertNotNull(sessionB)
        // A's own session object (captured at tearDown time) is a *different* instance from B's —
        // structurally, nothing A's teardown does can reach B's fields at all.
        assertEquals(1, fakeSessions.tornDownSessions.size)
        assertFalse(fakeSessions.tornDownSessions.single()!!.generation == sessionB!!.generation)
    }

    @Test
    fun `repeated Stop creates only one teardown job`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fakeSessions = FakeSessions(tearDownGate = gate)
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), this, fakeSessions)
        runner.start()

        val first = runner.stop()
        val second = runner.stop()
        val third = runner.stop()

        assertSame(first, second)
        assertSame(first, third)
        gate.complete(Unit)
        first.join()
        assertEquals(1, fakeSessions.events.count { it.startsWith("tearDown(") })
    }

    @Test
    fun `stop with nothing started is a safe no-op that still resolves to idle`() = runTest(testDispatcher) {
        val fakeSessions = FakeSessions()
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), this, fakeSessions)

        val job = runner.stop()
        job.join()

        assertEquals(1, fakeSessions.idleCount)
        assertEquals(listOf<FakeSession?>(null), fakeSessions.tornDownSessions)
    }

    @Test
    fun `discardStarting forgets the session without tearing anything down`() {
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), TestScopeStub, FakeSessions())
        runner.start()

        runner.discardStarting()

        assertNull(runner.currentSession)
    }

    @Test
    fun `a new session after a completed teardown is a genuinely distinct instance`() = runTest(testDispatcher) {
        val fakeSessions = FakeSessions()
        val coordinator = MonitoringLifecycleCoordinator()
        val runner = MonitoringSessionRunner(coordinator, this, fakeSessions)
        val sessionA = runner.start()
        val teardownJob = runner.stop()
        teardownJob.join()

        val sessionB = runner.start()

        assertNotNull(sessionA)
        assertNotNull(sessionB)
        assertTrue(sessionA !== sessionB)
    }

    @Test
    fun `wasActive is threaded through from the coordinator to tearDown`() = runTest(testDispatcher) {
        val fakeSessions = FakeSessions()
        val runner = MonitoringSessionRunner(MonitoringLifecycleCoordinator(), this, fakeSessions)
        runner.start() // STARTING only, never reaches ACTIVE

        val job = runner.stop()
        job.join()

        assertTrue(fakeSessions.events.single { it.startsWith("tearDown(") }.contains("wasActive=false"))
    }
}

/** A no-op [kotlinx.coroutines.CoroutineScope] for the handful of tests above that never actually
 * launch a coroutine (pure [MonitoringSessionRunner.start]/[MonitoringSessionRunner.discardStarting]
 * calls) — using it instead of [kotlinx.coroutines.test.runTest] keeps those tests synchronous and
 * simple. Any test that calls [MonitoringSessionRunner.stop] uses `runTest(testDispatcher)`'s own
 * scope instead. */
private object TestScopeStub : kotlinx.coroutines.CoroutineScope {
    override val coroutineContext: kotlin.coroutines.CoroutineContext =
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
}
