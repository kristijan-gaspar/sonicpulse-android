package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStartTrackerTest {

    @Test
    fun `begin is accepted when nothing is currently running`() {
        val tracker = LocationStartTracker()
        val token = Any()

        val result = tracker.begin(token) { }

        assertEquals(LocationStartTracker.BeginResult.Accepted(token), result)
        assertTrue(tracker.isCurrent(token))
    }

    @Test
    fun `a duplicate begin while an attempt is pending is rejected, preventing duplicate registration`() {
        val tracker = LocationStartTracker()
        tracker.begin(Any()) { }

        val result = tracker.begin(Any()) { }

        assertEquals(LocationStartTracker.BeginResult.Rejected, result)
    }

    @Test
    fun `a duplicate begin after a confirmed Started is also rejected`() {
        val tracker = LocationStartTracker()
        val token = Any()
        tracker.begin(token) { }
        tracker.complete(token, LocationStartResult.Started)

        val result = tracker.begin(Any()) { }

        assertEquals(LocationStartTracker.BeginResult.Rejected, result)
    }

    @Test
    fun `complete for the current token delivers the result exactly once`() {
        val tracker = LocationStartTracker()
        val token = Any()
        var delivered = 0
        tracker.begin(token) { delivered++ }
        val failure = LocationStartResult.Failed(RuntimeException("boom"))

        val first = tracker.complete(token, failure)
        val second = tracker.complete(token, failure)

        assertTrue(first is LocationStartTracker.CompleteResult.Deliver)
        (first as LocationStartTracker.CompleteResult.Deliver).onResult(failure)
        assertEquals(1, delivered)
        assertEquals(LocationStartTracker.CompleteResult.Stale, second)
    }

    @Test
    fun `restart is accepted after an async startup failure`() {
        val tracker = LocationStartTracker()
        val firstToken = Any()
        tracker.begin(firstToken) { }
        tracker.complete(firstToken, LocationStartResult.Failed(RuntimeException("boom")))

        val secondToken = Any()
        val result = tracker.begin(secondToken) { }

        assertEquals(LocationStartTracker.BeginResult.Accepted(secondToken), result)
    }

    @Test
    fun `restart is accepted after a permission-denied startup result`() {
        val tracker = LocationStartTracker()
        val firstToken = Any()
        tracker.begin(firstToken) { }
        tracker.complete(firstToken, LocationStartResult.PermissionDenied)

        val secondToken = Any()
        val result = tracker.begin(secondToken) { }

        assertEquals(LocationStartTracker.BeginResult.Accepted(secondToken), result)
    }

    @Test
    fun `stop while a start is pending reports Cancelled and allows a later restart`() {
        val tracker = LocationStartTracker()
        val token = Any()
        var lastResult: LocationStartResult? = null
        tracker.begin(token) { lastResult = it }

        val onResult = tracker.stop()
        onResult?.invoke(LocationStartResult.Cancelled)

        assertEquals(LocationStartResult.Cancelled, lastResult)
        assertFalse(tracker.isCurrent(token))

        val restartToken = Any()
        assertEquals(LocationStartTracker.BeginResult.Accepted(restartToken), tracker.begin(restartToken) { })
    }

    @Test
    fun `stop with nothing pending is a harmless no-op`() {
        val tracker = LocationStartTracker()

        val onResult = tracker.stop()

        assertEquals(null, onResult)
    }

    @Test
    fun `a late success after cancellation is stale and is not delivered`() {
        val tracker = LocationStartTracker()
        val token = Any()
        var deliveries = 0
        tracker.begin(token) { deliveries++ }
        tracker.stop() // invalidates the token before the async result arrives

        val result = tracker.complete(token, LocationStartResult.Started)

        assertEquals(LocationStartTracker.CompleteResult.Stale, result)
        assertEquals(0, deliveries)
    }

    @Test
    fun `a late failure after cancellation is stale and is not delivered`() {
        val tracker = LocationStartTracker()
        val token = Any()
        var deliveries = 0
        tracker.begin(token) { deliveries++ }
        tracker.stop()

        val result = tracker.complete(token, LocationStartResult.Failed(RuntimeException("late")))

        assertEquals(LocationStartTracker.CompleteResult.Stale, result)
        assertEquals(0, deliveries)
    }

    @Test
    fun `isCurrent is false for a token from a session that was stopped, even if a new session started`() {
        val tracker = LocationStartTracker()
        val staleToken = Any()
        tracker.begin(staleToken) { }
        tracker.stop()

        val newToken = Any()
        tracker.begin(newToken) { }

        assertFalse(tracker.isCurrent(staleToken))
        assertTrue(tracker.isCurrent(newToken))
    }

    @Test
    fun `isCurrent remains true across a Started completion so late location updates are still accepted`() {
        val tracker = LocationStartTracker()
        val token = Any()
        tracker.begin(token) { }

        tracker.complete(token, LocationStartResult.Started)

        assertTrue(tracker.isCurrent(token))
    }
}
