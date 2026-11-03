package mesh.office.bambuwatch

import mesh.office.bambuwatch.ConnectionPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {

    @Test
    fun unconfiguredAppDoesNothing() {
        assertEquals(Action.NOTHING, ConnectionPolicy.onStart(configured = false, running = false, connected = false))
    }

    @Test
    fun returningWithNoConnectionStartsOne() {
        assertEquals(Action.CONNECT, ConnectionPolicy.onStart(true, running = false, connected = false))
    }

    /** the important one: still "running" from before the trip to the background, but dead */
    @Test
    fun returningWithADeadSocketReconnects() {
        assertEquals(Action.RECONNECT, ConnectionPolicy.onStart(true, running = true, connected = false))
    }

    @Test
    fun returningToALiveConnectionChangesNothing() {
        assertEquals(Action.NOTHING, ConnectionPolicy.onStart(true, running = true, connected = true))
    }

    @Test
    fun aRememberedStateIsStaleNotOffline() {
        assertEquals("stale", ConnectionPolicy.staleWord(true))
        assertEquals("offline", ConnectionPolicy.staleWord(false))
    }

    @Test
    fun backgroundedFailuresAreNotRetried() {
        assertNull(ConnectionPolicy.onFailure(attempt = 0, wantConnected = false))
    }

    @Test
    fun foregroundFailuresBackOffAndThenAskForHelp() {
        // attempt n means "n failures have already happened"
        val plans = (0..7).mapNotNull { ConnectionPolicy.onFailure(it, wantConnected = true) }
        assertTrue("waits must not shrink", plans.zipWithNext().all { (a, b) -> b.waitMs >= a.waitMs })
        assertFalse("must not give up immediately", plans.first().giveUp)
        // the boundary, stated exactly: the 8th failure is the first to stop retrying
        assertTrue("attempts 1..7 must keep retrying",
            plans.take(7).none { it.giveUp })
        assertTrue("the 8th failure must stop and say so", plans[7].giveUp)
        val total = plans.take(7).sumOf { it.waitMs }
        assertTrue("should reach the give-up point in a couple of minutes, not an hour", total in 60_000..300_000)
    }
}

/** The bug I nearly shipped: our own reconnect closes the old client, which fires connectionLost. */
class ConnectionLostDecisionTest {

    @Test
    fun aDropWhileForegroundAndRunningIsRetried() {
        org.junit.Assert.assertTrue(ConnectionPolicy.onLost(wantConnected = true, wasRunning = true))
    }

    @Test
    fun aDropWeCausedOurselvesIsNotRetriedAgain() {
        org.junit.Assert.assertFalse(ConnectionPolicy.onLost(wantConnected = true, wasRunning = false))
    }

    @Test
    fun aDropWhileBackgroundedIsNotRetried() {
        org.junit.Assert.assertFalse(ConnectionPolicy.onLost(wantConnected = false, wasRunning = true))
        org.junit.Assert.assertFalse(ConnectionPolicy.onLost(wantConnected = false, wasRunning = false))
    }
}
