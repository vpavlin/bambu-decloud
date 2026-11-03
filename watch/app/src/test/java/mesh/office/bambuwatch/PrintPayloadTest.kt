package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The print payloads, checked against the shapes the printer actually accepts — a documentation
 * reference and a working integration — rather than against my memory of them.
 */
class PrintPayloadTest {

    private fun json(s: String) = org.json.JSONObject(s).getJSONObject("print")

    @Test
    fun aBareGcodeIsStartedWithGcodeFileAtAnAbsolutePath() {
        val p = json(Commands.printCard("5", CardFile("Bambu Spool_plate_1.gcode", "Bambu Spool_plate_1.gcode", 1, 0)))
        assertEquals("gcode_file", p.getString("command"))
        // measured 23-10: a bare name is answered `result: fail`
        assertEquals("/cache/Bambu Spool_plate_1.gcode", p.getString("param"))
    }

    @Test
    fun aThreeMfIsStartedWithProjectFileCarryingAnFtpUrl() {
        val p = json(Commands.printCard("5", CardFile("test.3mf", "test.3mf", 1, 0)))
        assertEquals("project_file", p.getString("command"))
        assertEquals("Metadata/plate_1.gcode", p.getString("param"))
        assertEquals("ftp:///cache/test.3mf", p.getString("url"))
        assertEquals("", p.getString("file"))
        assertEquals("test", p.getString("subtask_name"))
    }

    @Test
    fun theProjectFieldsAreSpelledTheWayOneOfTheTwoSourcesSays() {
        val a = json(Commands.projectFile("1", "x.3mf", "x.3mf"))
        assertTrue("the wiki spelling", a.has("bed_levelling"))
        assertEquals("", a.getString("ams_mapping"))
        val b = json(Commands.projectFileAlt("1", "x.3mf", "x.3mf"))
        assertTrue("the working-integration spelling", b.has("bed_leveling"))
        assertEquals(0, b.getJSONArray("ams_mapping").getInt(0))
        listOf("project_id", "profile_id", "task_id", "subtask_id").forEach {
            assertEquals("0", a.getString(it))
            assertEquals("0", b.getString(it))
        }
        assertEquals("auto", a.getString("bed_type"))
    }

    @Test
    fun aRootFileKeepsItsPathInTheUrl() {
        val p = json(Commands.printCard("1", CardFile("proj.3mf", "/proj.3mf", 1, 0)))
        assertEquals("ftp:///proj.3mf", p.getString("url"))
    }

    @Test
    fun printingIsRefusedWhileReadOnly() {
        val payload = Commands.printCard("1", CardFile("a.gcode", "a.gcode", 1, 0))
        assertEquals(Commands.READ_ONLY_REFUSAL, Commands.refuseIfReadOnly(true, payload))
    }

    /** a bare name means the cache folder; that is where Studio puts printable jobs */
    @Test
    fun pathsAreMadeAbsolute() {
        assertEquals("/cache/80365_plate_1.gcode", Commands.absolute("80365_plate_1.gcode"))
        assertEquals("/proj.gcode", Commands.absolute("/proj.gcode"))
        assertEquals("/cache/x.gcode", Commands.absolute("cache/x.gcode"))
    }

    /** the journal must never say "a command": that hid which payload was sent */
    @Test
    fun describeNamesTheCommandAndItsParam() {
        val d = Commands.describe(Commands.gcodeFile("1", "a.gcode"))
        assertTrue(d.contains("gcode_file") && d.contains("/cache/a.gcode"))
        assertTrue(Commands.describe("rubbish").startsWith("unreadable payload"))
        assertFalse(Commands.describe(Commands.gcodeFile("1", "a.gcode")).contains("a command"))
    }
}

/**
 * What a running print actually looks like — measured, not documented.
 * The printer named its own job `RasPi5Case_withTopMountNVME.gcode.3mf`, a bare name, while the
 * app was sending /cache/<name> because a wiki said "absolute paths".
 */
class PrintCandidatesTest {

    private fun cmds(payload: String) = org.json.JSONObject(payload).getJSONObject("print")

    @Test
    fun aProjectIsTriedThreeWaysWithTheCacheUrlFirst() {
        val c = Commands.printCandidates("1", CardFile("job.3mf", "job.3mf", 1, 0))
        assertEquals(3, c.size)
        assertEquals("project_file", cmds(c[0]).getString("command"))
        assertEquals("ftp:///cache/job.3mf", cmds(c[0]).getString("url"))
        assertEquals("ftp:///job.3mf", cmds(c[1]).getString("url"))
        assertEquals("job.3mf", cmds(c[2]).getString("file"))
        assertEquals("", cmds(c[2]).getString("url"))
    }

    /** the printer's own vocabulary: a bare name, no /cache prefix */
    @Test
    fun aRawGcodeIsTriedWithTheBareNameFirst() {
        val c = Commands.printCandidates("1", CardFile("a.gcode", "a.gcode", 1, 0))
        assertEquals("gcode_file", cmds(c[0]).getString("command"))
        assertEquals("a.gcode", cmds(c[0]).getString("param"))
        assertEquals("/cache/a.gcode", cmds(c[1]).getString("param"))
    }

    @Test
    fun projectsAreTheOnesMarkedPrintable() {
        org.junit.Assert.assertTrue(Commands.looksPrintable("job.3mf"))
        org.junit.Assert.assertTrue(Commands.looksPrintable("x.gcode.3mf"))
        org.junit.Assert.assertFalse(Commands.looksPrintable("x.gcode"))
    }
}
