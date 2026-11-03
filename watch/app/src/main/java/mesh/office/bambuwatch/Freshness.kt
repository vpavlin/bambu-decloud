package mesh.office.bambuwatch

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How old is what we are showing, and how long to wait before trying again.
 *
 * Both are pure so they can be tested without a phone: the screen that says "last report
 * 21:04 (12 min ago)" is only honest if the arithmetic behind it is.
 */
object Age {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** wall-clock time of the last report, e.g. "21:04" */
    fun at(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        hhmm.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** coarse age, for a line that must stay readable at a glance */
    fun label(nowMs: Long, thenMs: Long): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 10 -> "just now"
            s < 90 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}

/**
 * Reconnect backoff. Android kills sockets while the app is in the background and the printer
 * only serves a few clients, so retrying in a tight loop is both useless and rude: the wait
 * grows, and stops growing at half a minute so a returning user is not left waiting.
 */
object Backoff {

    private val stepsMs = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

    fun delayMs(attempt: Int): Long = stepsMs[attempt.coerceIn(0, stepsMs.size - 1)]

    /** after this many failures the user is told to look at the settings rather than keep waiting */
    const val GIVE_UP_AFTER = 8
}
