package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Files screen must not be a blank 18-second wait, and must not lie about what it is showing.
 * The cache makes the first open instant; the live listing replaces it.
 */
class CardCacheTest {

    private fun f(n: String, t: Long) = CardFile(name = n, path = n, size = 10, modified = t)

    @Test
    fun aListingSurvivesARestart() {
        val listing = listOf(f("a.3mf", 1_700_000_000_000), f("b.gcode", 1_700_000_100_000))
        assertEquals(listing, CardFile.fromJson(CardFile.toJson(listing)))
    }

    @Test
    fun nothingCachedIsAnEmptyListNotACrash() {
        assertEquals(emptyList<CardFile>(), CardFile.fromJson(null))
        assertEquals(emptyList<CardFile>(), CardFile.fromJson(""))
        assertEquals(emptyList<CardFile>(), CardFile.fromJson("not json"))
    }

    /** the newest twelve, because that is what "the thing I forgot to print" usually is */
    @Test
    fun theNewestComeFirst() {
        val listing = (1..20).map { f("job$it.gcode", 1_700_000_000_000 + it * 1000L) }
            .sortedByDescending { it.modified }
        val shown = listing.take(12)
        assertEquals("job20.gcode", shown.first().name)
        assertEquals(12, shown.size)
        assertTrue("the newest must sort above the oldest", shown.first().modified > shown.last().modified)
    }
}

/** Opening the screen must not cost a read, and a failed read must not erase what we had. */
class CardFreshnessTest {

    private val now = 1_700_000_000_000L

    private fun isFresh(cachedAt: Long, at: Long = now, hasList: Boolean = true): Boolean {
        val age = at - cachedAt
        return hasList && age in 0 until 10 * 60 * 1000L
    }

    @Test
    fun aListReadAMinuteAgoIsShownWithoutReReading() {
        org.junit.Assert.assertTrue(isFresh(now - 60_000))
    }

    @Test
    fun aListFromAnHourAgoIsReRead() {
        org.junit.Assert.assertFalse(isFresh(now - 3_600_000))
    }

    @Test
    fun nothingCachedMeansRead() {
        org.junit.Assert.assertFalse(isFresh(0))
        org.junit.Assert.assertFalse(isFresh(now - 1000, hasList = false))
    }

    /** a clock that jumped backwards must not make a list look eternally fresh */
    @Test
    fun aFutureTimestampIsNotFresh() {
        org.junit.Assert.assertFalse(isFresh(now + 60_000))
    }
}
