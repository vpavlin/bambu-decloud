package mesh.office.bambuwatch

/**
 * What to do about the connection, given what we know. Android takes the network away
 * whenever it likes, so "connected" is never a fact that persists across a trip to another
 * app; the decisions below are the parts worth getting right, and they are pure so they can
 * be tested without a device.
 */
object ConnectionPolicy {

    enum class Action { NOTHING, CONNECT, RECONNECT, DISCONNECT }

    /**
     * Coming back to the foreground. Three cases, and only three:
     *  - not configured: nothing to do
     *  - not running: connect
     *  - running but not connected: the socket is a corpse Paho has not noticed yet — reconnect
     */
    fun onStart(configured: Boolean, running: Boolean, connected: Boolean): Action = when {
        !configured -> Action.NOTHING
        !running -> Action.CONNECT
        !connected -> Action.RECONNECT
        else -> Action.NOTHING
    }

    /** whether to show "No fresh data" rather than "Offline": a remembered state is not the same as a failure */
    fun staleWord(hasRememberedState: Boolean): String = if (hasRememberedState) "stale" else "offline"

    /**
     * A socket dropped under us. Retry only when the user still wants a connection AND we did not
     * drop it ourselves — our own reconnect closes the old client before opening a new one, and
     * without the second condition that closure would summon another retry, forever.
     */
    fun onLost(wantConnected: Boolean, wasRunning: Boolean): Boolean =
        wantConnected && wasRunning

    data class Retry(val waitMs: Long, val giveUp: Boolean)

    /**
     * A failure only earns a retry while the user still wants a connection. Backgrounded
     * failures are not retried: the OS will give the network back, and a retry loop there is
     * how an app becomes a battery complaint.
     */
    fun onFailure(attempt: Int, wantConnected: Boolean): Retry? {
        if (!wantConnected) return null
        val next = attempt + 1
        return Retry(Backoff.delayMs(attempt), next >= Backoff.GIVE_UP_AFTER)
    }
}
