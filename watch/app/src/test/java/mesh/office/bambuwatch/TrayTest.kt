package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seen on the real screen: the A1's empty AMS slot rendered as "?" over "?", which reads as
 * "the app does not know" rather than "nothing is loaded in there". The printer is not hiding
 * anything — it reports no tray_type for an empty slot.
 */
class TrayTest {

    private fun fixture() = javaClass.classLoader!!.getResource("real-a1-report.json")!!.readText()

    @Test
    fun anEmptySlotIsNotLoaded() {
        assertFalse(Tray(slot = "0", material = null, colorHex = null, remain = null, active = false).isLoaded)
        assertFalse(Tray(slot = "0", material = "", colorHex = "00000000", remain = null, active = false).isLoaded)
    }

    @Test
    fun aSpoolIsLoaded() {
        assertTrue(Tray(slot = "1", material = "PLA", colorHex = "FFFFFFFF", remain = 100, active = false).isLoaded)
    }

    @Test
    fun theRealReportHasOneEmptySlotAndThreeSpools() {
        val st = Reports.parse(org.json.JSONObject(fixture()), null)
        assertEquals("slot count", 4, st.trays.size)
        val bySlot = st.trays.associateBy { it.slot }
        assertFalse("slot 0 is empty on this printer", bySlot["0"]!!.isLoaded)
        listOf("1", "2", "3").forEach { assertTrue("slot $it is loaded", bySlot[it]!!.isLoaded) }
        assertEquals("PLA", bySlot["1"]!!.material)
        assertEquals(100, bySlot["1"]!!.remain)
    }
}
