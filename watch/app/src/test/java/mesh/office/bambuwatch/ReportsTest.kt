package mesh.office.bambuwatch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The printer sends one full report and then partial ones. Everything the UI shows
 * depends on those partials being MERGED rather than replacing state, so that is what
 * these tests pin down. The JSON here is the shape an A1 sends.
 */
class ReportsTest {

    private val full = """
    {"print":{"command":"push_status","gcode_state":"RUNNING","subtask_name":"benchy.3mf",
      "mc_percent":47,"layer_num":63,"total_layer_num":134,"mc_remaining_time":21,
      "nozzle_temper":219.8,"bed_temper":60.4,"chamber_temper":0,"print_error":0,"hms":[],
      "ams":{"tray_now":"1","ams":[{"id":"0","tray":[
        {"id":"0","tray_type":"PLA","tray_color":"FF6A13FF","remain":72},
        {"id":"1","tray_type":"PETG","tray_color":"1E88E5FF","remain":41},
        {"id":"2","tray_type":"PLA","tray_color":"E8E8E8FF","remain":-1},
        {"id":"3","tray_type":"","tray_color":"00000000","remain":0}]}]}},
     "info":{"command":"get_version","serial_number":"YOUR_SERIAL","machine_name":"Bambu Lab A1"}}
    """.trimIndent()

    @Test
    fun fullReportParses() {
        val st = Reports.parse(JSONObject(full), null)
        assertEquals("RUNNING", st.gcodeState)
        assertEquals("printing", st.statusWord)
        assertEquals(47, st.progress)
        assertEquals(63, st.layer)
        assertEquals(134, st.totalLayers)
        assertEquals(21, st.remainingMin)
        assertEquals(219.8, st.nozzle!!, 0.001)
        assertEquals(60.4, st.bed!!, 0.001)
        assertEquals("benchy.3mf", st.fileName)
        assertEquals("YOUR_SERIAL", st.serial)
        assertEquals("Bambu Lab A1", st.machine)
    }

    @Test
    fun chamberZeroIsNotShown() {
        // an A1 has no chamber; the report still sends chamber_temper: 0
        val st = Reports.parse(JSONObject(full), null)
        assertTrue("0 °C must not become a chamber tile", !st.hasChamber)
    }

    @Test
    fun partialUpdateMergesInsteadOfReplacing() {
        var merged = JSONObject(full)
        merged = Reports.merge(merged, JSONObject("""{"print":{"mc_percent":48,"layer_num":64}}"""))
        val st = Reports.parse(merged, null)
        assertEquals(48, st.progress)
        assertEquals(64, st.layer)
        // everything the partial did not mention must survive
        assertEquals("RUNNING", st.gcodeState)
        assertEquals(134, st.totalLayers)
        assertEquals("benchy.3mf", st.fileName)
        assertEquals(4, st.trays.size)
        assertEquals(219.8, st.nozzle!!, 0.001)
    }

    @Test
    fun nestedMergeKeepsSiblingKeys() {
        var merged = JSONObject(full)
        merged = Reports.merge(merged, JSONObject("""{"print":{"ams":{"tray_now":"0"}}}"""))
        val st = Reports.parse(merged, null)
        assertEquals("0", st.activeTray)
        assertEquals("merging must not wipe the trays", 4, st.trays.size)
        assertTrue(st.trays[0].active)
        assertTrue(!st.trays[1].active)
    }

    @Test
    fun pauseStateWord() {
        var merged = JSONObject(full)
        merged = Reports.merge(merged, JSONObject("""{"print":{"gcode_state":"PAUSE"}}"""))
        val st = Reports.parse(merged, null)
        assertEquals("paused", st.statusWord)
        assertEquals("progress must survive a state-only update", 47, st.progress)
    }

    @Test
    fun traysAreParsedWithColourAndRemainder() {
        val st = Reports.parse(JSONObject(full), null)
        assertEquals(4, st.trays.size)
        val t0 = st.trays[0]
        assertEquals("PLA", t0.material)
        assertEquals("#FF6A13", t0.colorHex)
        assertEquals(72, t0.remain)
        // -1 means "the printer does not know", not "empty"
        assertNull(st.trays[2].remain)
        // an empty slot has no material
        assertNull(st.trays[3].material)
    }

    @Test
    fun colourIsNormalised() {
        val st = Reports.parse(JSONObject("""
          {"print":{"ams":{"ams":[{"tray":[{"id":"0","tray_type":"PLA","tray_color":"1E88E5FF"}]}]}}}
        """.trimIndent()), null)
        assertEquals("#1E88E5", st.trays[0].colorHex)
    }

    @Test
    fun hmsAndErrorSurface() {
        val st = Reports.parse(JSONObject("""
          {"print":{"gcode_state":"FAILED","print_error":83935248,
            "hms":[{"attr":33558529,"code":"0300010000020001"}]}}
        """.trimIndent()), null)
        assertEquals("error", st.statusWord)
        assertEquals(83935248L, st.printError)
        assertEquals(1, st.hms.size)
        assertNotNull(st.hms.first())
    }

    @Test
    fun serialFallsBackToSettings() {
        val st = Reports.parse(JSONObject("""{"print":{"gcode_state":"IDLE"}}"""), "FROM-CERT")
        assertEquals("FROM-CERT", st.serial)
    }

    @Test
    fun unknownStateIsPassedThroughNotInvented() {
        val st = Reports.parse(JSONObject("""{"print":{"gcode_state":"WHATEVER"}}"""), null)
        assertEquals("whatever", st.statusWord)
    }

    @Test
    fun offlineIsTheDefault() {
        val st = PrinterState()
        assertEquals("offline", st.statusWord)
        assertTrue(!st.connected)
    }
}

/**
 * A real report captured from the office A1, kept as a fixture: my mock agreed with whatever
 * I believed, and this is the only data that cannot.
 */
class RealReportTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResource("real-a1-report.json")!!.readText()

    @Test
    fun parsesARealA1Report() {
        val st = Reports.parse(org.json.JSONObject(fixture()), "YOUR_SERIAL")
        assertEquals("finished", st.statusWord)
        assertEquals(100, st.progress)
        assertEquals(45, st.layer)
        assertEquals(45, st.totalLayers)
        assertEquals("lambda-bookend-small_PRINT-THIS", st.fileName)
        assertEquals(25.75, st.nozzle!!, 0.01)
        assertEquals(26.4375, st.bed!!, 0.01)
    }

    @Test
    fun aRealReportHasNoInfoBlockSoTheSerialComesFromTheCertificate() {
        val st = Reports.parse(org.json.JSONObject(fixture()), "FROM-CERT")
        assertEquals("FROM-CERT", st.serial)
    }

    @Test
    fun theA1SentinelChamberValueIsNotShown() {
        // the printer reports chamber_temper = 5 and has no chamber sensor
        val st = Reports.parse(org.json.JSONObject(fixture()), null)
        assertEquals(5.0, st.chamber!!, 0.001)
        assertTrue("5 °C must not become a chamber tile", !st.hasChamber)
    }

    @Test
    fun traysFromARealReport() {
        val st = Reports.parse(org.json.JSONObject(fixture()), null)
        assertEquals(4, st.trays.size)
        // slot 0 is empty in the real report
        assertNull(st.trays[0].material)
        assertEquals("PLA", st.trays[1].material)
        assertEquals("#FFFFFF", st.trays[1].colorHex)
        assertEquals(100, st.trays[1].remain)
    }
}
