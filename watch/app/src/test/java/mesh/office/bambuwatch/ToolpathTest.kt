package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ToolpathTest {

    /** the stream exactly as render.py writes it: coords first, then the feature bytes */
    private fun stream(layers: List<Pair<Float, List<FloatArray>>>): ByteArray {
        val names = listOf("Custom", "Outer wall", "Top surface")
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(4 + 4 + 16 + 4).order(ByteOrder.LITTLE_ENDIAN)
        head.put("SLT2".toByteArray())
        head.putInt(layers.size)
        head.putFloat(256f); head.putFloat(256f); head.putFloat(60f); head.putFloat(100f)
        head.putInt(names.size)
        out.write(head.array())
        for (n in names) {
            val b = n.toByteArray()
            out.write(b.size)
            out.write(b)
            out.write(ByteArray(31 - b.size))
        }
        for ((z, segs) in layers) {
            val lh = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            lh.putFloat(z); lh.putInt(segs.size)
            out.write(lh.array())
            val coords = ByteBuffer.allocate(segs.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (s in segs) {
                coords.putShort((s[0] * 100).toInt().toShort())
                coords.putShort((s[1] * 100).toInt().toShort())
                coords.putShort((s[2] * 100).toInt().toShort())
                coords.putShort((s[3] * 100).toInt().toShort())
            }
            out.write(coords.array())
            for (s in segs) out.write(s[4].toInt())
        }
        return out.toByteArray()
    }

    @Test fun `parses layers and coordinates`() {
        val bytes = stream(listOf(
            0.2f to listOf(floatArrayOf(10f, 10f, 20f, 20f, 1f)),
            0.4f to listOf(floatArrayOf(30f, 30f, 40f, 40f, 2f), floatArrayOf(50f, 50f, 60f, 60f, 1f)),
        ))
        val tp = Toolpath.parse(bytes)
        assertEquals(2, tp.layers.size)
        assertEquals(1, tp.layers[0].count)
        assertEquals(2, tp.layers[1].count)
        assertEquals(10f, tp.layers[0].seg[0], 0.01f)
        assertEquals(20f, tp.layers[0].seg[2], 0.01f)
        // the feature byte belongs to the segment, and is not part of the coordinates
        // the second layer was written with features 2 then 1: the ids must follow the
        // segments, which is exactly what an interleaved reader gets wrong
        assertEquals("Top surface", tp.names[tp.layers[1].feat[0].toInt()])
        assertEquals("Outer wall", tp.names[tp.layers[1].feat[1].toInt()])
        assertEquals(256f, tp.bedX, 0.01f)
    }

    @Test fun `the fit ignores the first layer, where the purge line lives`() {
        val bytes = stream(listOf(
            0.2f to listOf(floatArrayOf(0f, 0f, 10f, 230f, 0f)),   // a line across the bed
            0.4f to listOf(floatArrayOf(120f, 120f, 140f, 140f, 1f)),
            0.6f to listOf(floatArrayOf(121f, 121f, 139f, 139f, 1f)),
        ))
        val tp = Toolpath.parse(bytes)
        assertEquals(120f, tp.box.x0, 0.01f)
        assertEquals(140f, tp.box.x1, 0.01f)
    }

    @Test fun `a stream that is not ours is refused`() {
        val bad = ByteArray(8) { 0x41 }
        var threw = false
        try { Toolpath.parse(bad) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected a refusal, not garbage geometry", threw)
    }
}
