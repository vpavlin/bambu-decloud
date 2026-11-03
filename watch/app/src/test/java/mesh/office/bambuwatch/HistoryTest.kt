package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private val t0 = 1_700_000_000_000L
    private fun st(g: String, name: String? = "job-a", pct: Int? = null, file: String? = null) =
        PrinterState(gcodeState = g, statusWord = "x", fileName = name, progress = pct, gcodeFile = file)

    @Test
    fun aPrintingReportOpensAJob() {
        val h = History.advance(emptyList(), st("RUNNING", pct = 3), t0)
        assertEquals(1, h.size)
        assertTrue(h[0].running)
        assertNull(h[0].result)
    }

    /** the printer sends a report every few seconds; that must not become twenty jobs */
    @Test
    fun driftingProgressDoesNotOpenASecondJob() {
        var h = History.advance(emptyList(), st("RUNNING", pct = 3), t0)
        listOf(10, 20, 30).forEachIndexed { i, p -> h = History.advance(h, st("RUNNING", pct = p), t0 + 1000L * (i + 1)) }
        assertEquals(1, h.size)
        assertEquals(30, h[0].lastPercent)
    }

    @Test
    fun finishingClosesTheJobWithADurationAndResult() {
        var h = History.advance(emptyList(), st("RUNNING", pct = 1), t0)
        h = History.advance(h, st("FINISH", pct = 100), t0 + 41 * 60_000L)
        assertEquals(1, h.size)
        assertEquals("FINISH", h[0].result)
        assertEquals(41, h[0].durationMin())
        assertFalse(h[0].running)
    }

    @Test
    fun aJobSeenOnlyWhenItEndedIsRecordedWithoutPretendingToKnowTheDuration() {
        val h = History.advance(emptyList(), st("FINISH", pct = 100), t0)
        assertEquals(1, h.size)
        assertEquals(0, h[0].durationMin())
        assertEquals("FINISH", h[0].result)
    }

    @Test
    fun aSecondPrintOpensASecondJob() {
        var h = History.advance(emptyList(), st("RUNNING", name = "a"), t0)
        h = History.advance(h, st("FINISH", name = "a"), t0 + 60_000)
        h = History.advance(h, st("RUNNING", name = "b"), t0 + 120_000)
        assertEquals(2, h.size)
        assertEquals("b", h[0].name)
        assertTrue(h[0].running)
    }

    /** reprint is only offered when the printer told us where the file is */
    @Test
    fun aJobWithoutAFilePathIsNotReprintable() {
        val h = History.advance(emptyList(), st("RUNNING", file = null), t0)
        assertFalse(h[0].reprintable)
        val h2 = History.advance(emptyList(), st("RUNNING", file = "Metadata/plate_1.gcode"), t0)
        assertTrue(h2[0].reprintable)
    }

    @Test
    fun historySurvivesARestart() {
        var h = History.advance(emptyList(), st("RUNNING", file = "Metadata/plate_1.gcode", pct = 5), t0)
        h = History.advance(h, st("FINISH", pct = 100), t0 + 30 * 60_000)
        val back = History.fromJson(History.toJson(h))
        assertEquals(h, back)
    }

    @Test
    fun rubbishInTheStoreIsNotACrash() {
        assertEquals(emptyList<JobRecord>(), History.fromJson("not json"))
        assertEquals(emptyList<JobRecord>(), History.fromJson(null))
        assertEquals(emptyList<JobRecord>(), History.fromJson("[]"))
    }

    @Test
    fun theListIsBounded() {
        var h = emptyList<JobRecord>()
        repeat(History.MAX + 5) { i ->
            h = History.advance(h, st("RUNNING", name = "job$i"), t0 + i * 1000L)
            h = History.advance(h, st("FINISH", name = "job$i"), t0 + i * 1000L + 500)
        }
        assertEquals(History.MAX, h.size)
    }
}
