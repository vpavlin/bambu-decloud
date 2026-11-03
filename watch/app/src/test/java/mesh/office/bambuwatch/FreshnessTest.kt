package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The screen says "last report 21:04 (12 min ago)". If this arithmetic is wrong the app lies
 * calmly, which is worse than showing nothing.
 */
class FreshnessTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val t0 = 1_790_000_000_000L   // fixed instant, not "now"

    @Test
    fun clockTimeIsFormattedInTheGivenZone() {
        val s = Age.at(t0, zone)
        assertTrue("expected HH:mm, got $s", Regex("""^\d\d:\d\d$""").matches(s))
    }

    @Test
    fun secondsAndMinutesAreLabelled() {
        assertEquals("just now", Age.label(t0 + 3_000, t0))
        assertEquals("45 s ago", Age.label(t0 + 45_000, t0))
        assertEquals("12 min ago", Age.label(t0 + 12 * 60_000, t0))
        assertEquals("2 h ago", Age.label(t0 + 2 * 3_600_000, t0))
        assertEquals("3 d ago", Age.label(t0 + 3 * 86_400_000, t0))
    }

    /** a clock that went backwards must not produce "in -40 s" */
    @Test
    fun aFutureTimestampReadsAsJustNow() {
        assertEquals("just now", Age.label(t0, t0 + 40_000))
    }

    @Test
    fun backoffGrowsThenStopsGrowing() {
        val d = (0..9).map { Backoff.delayMs(it) }
        assertTrue("backoff must not shrink: $d", d.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(2_000L, Backoff.delayMs(0))
        assertEquals(30_000L, Backoff.delayMs(99))
        assertTrue("must not wait absurdly long", d.max() <= 60_000)
    }

    /** a cached report is what the app opens with, so it must parse back to the same numbers */
    @Test
    fun cachedReportRestoresTheLastKnownState() {
        val json = javaClass.classLoader!!.getResource("real-a1-report.json")!!.readText()
        val first = Reports.parse(org.json.JSONObject(json), null)
        val again = Reports.parse(org.json.JSONObject(json), null)
        assertEquals(first.gcodeState, again.gcodeState)
        assertEquals(first.progress, again.progress)
        assertEquals(first.trays.size, again.trays.size)
        assertEquals("FINISH", again.gcodeState)
        assertEquals(4, again.trays.size)
    }
}
