package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The slicer client against the real service on the mesh. Skipped unless
 * SLICER_URL is set, like the printer's own live test.
 *
 *   SLICER_URL=http://slicer.proteus.office.mesh gradle testDebugUnitTest --tests '*SlicerLiveTest'
 */
class SlicerLiveTest {

    private val base = System.getenv("SLICER_URL").orEmpty()

    private fun model(): File {
        // a 20 mm cube as ASCII STL, small enough to slice in a second
        val sb = StringBuilder("solid cube\n")
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(20f, 0f, 0f),
            floatArrayOf(20f, 20f, 0f), floatArrayOf(0f, 20f, 0f),
            floatArrayOf(0f, 0f, 20f), floatArrayOf(20f, 0f, 20f),
            floatArrayOf(20f, 20f, 20f), floatArrayOf(0f, 20f, 20f),
        )
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        for (f in faces) {
            sb.append("facet normal 0 0 0\nouter loop\n")
            for (i in f) sb.append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n")
            sb.append("endloop\nendfacet\n")
        }
        sb.append("endsolid cube\n")
        val f = File.createTempFile("cube", ".stl")
        f.writeText(sb.toString())
        return f
    }

    @Test
    fun `uploads, slices, reads the toolpath, sends and clears up`() {
        assumeTrue("set SLICER_URL to run against the real slicer", base.isNotBlank())

        val presets = Slicer.presets(base)
        assertTrue("expected process presets", presets.process.isNotEmpty())
        assertTrue("expected filament presets", presets.filament.isNotEmpty())

        val before = Slicer.jobs(base).size
        val id = Slicer.upload(base, model(), mapOf(
            "process" to presets.defaultProcess,
            "filament" to presets.defaultFilament,
            "copies" to "1",
        ))
        var job = Slicer.job(base, id)
        var waited = 0
        while (job.busy && waited < 120) { Thread.sleep(1000); job = Slicer.job(base, id); waited++ }

        assertEquals("slicing failed: ${job.error}", "sliced", job.state)
        assertTrue("no estimate", job.seconds != null && job.grams!! > 0)
        assertTrue("no layers", (job.layers ?: 0) > 0)
        assertTrue("a 20 mm cube should be under 10 g, got ${job.grams}",
            job.grams!! < 10.0)

        val tp = Slicer.toolpath(base, id)
        assertTrue("no layers in the toolpath", tp.layers.isNotEmpty())
        assertTrue("names missing", tp.names.isNotEmpty())
        // the cube is 20 mm on a side: the fit must be near that, not the whole bed
        assertTrue("fit is ${tp.box.size()}, expected about 20 x 20",
            tp.box.spanX in 15f..30f && tp.box.spanY in 15f..30f)

        // send it: this is the only call that touches the printer's card
        val sent = Slicer.sendToPrinter(base, id)
        assertEquals("sending failed: ${sent.error}", "ready", sent.state)
        assertTrue("no card path", sent.cardPath?.endsWith(".gcode.3mf") == true)
        val card = Slicer.cardFile(sent)
        assertTrue("not printable from the card", card != null && CardFile.printable(card!!.name))
        assertTrue("wrong payload name", Commands.printCandidates("1", card!!).isNotEmpty())

        // and leave no trace: deleting the job removes its file from the card too
        Slicer.delete(base, id)
        assertEquals(before, Slicer.jobs(base).size)
    }
}
