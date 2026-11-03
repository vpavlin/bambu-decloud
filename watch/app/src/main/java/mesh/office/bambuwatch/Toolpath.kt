package mesh.office.bambuwatch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The toolpath the slicer serves for the 3D view: `GET /jobs/{id}/toolpath.bin`.
 *
 * Layout, little-endian throughout (see `render.py` on proteus):
 *
 * ```
 * "SLT2"
 * u32  layer count
 * f32  bed x, bed y, z max, Q      (Q = units per mm, 100 -> 0.01 mm)
 * u32  feature count
 * repeat: u8 length, 31 bytes name
 * repeat per layer:
 *      f32 z, u32 n
 *      n * (u16 x0, u16 y0, u16 x1, u16 y1)
 *      n * u8 feature index        <-- all the coordinates first, THEN the ids
 * ```
 *
 * That last line is the trap: an earlier browser build read the feature byte
 * interleaved with the coordinates and drew plausible-looking spaghetti. Keep the
 * parsing separate from the drawing so it can be tested without Android.
 */
class Toolpath(
    val bedX: Float,
    val bedY: Float,
    val zMax: Float,
    val names: List<String>,
    val layers: List<Layer>,
) {
    class Layer(val z: Float, val seg: FloatArray, val feat: ByteArray) {
        val count: Int get() = feat.size
    }

    /** the printed part only: the purge line on layer 0 would stretch a fit to half the bed */
    val box: Box by lazy {
        val use = if (layers.size > 2) layers.drop(1) else layers
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (l in use) for (i in 0 until l.count) {
            val ax = l.seg[i * 4]; val ay = l.seg[i * 4 + 1]
            val bx = l.seg[i * 4 + 2]; val by = l.seg[i * 4 + 3]
            if (ax < x0) x0 = ax; if (ax > x1) x1 = ax
            if (bx < x0) x0 = bx; if (bx > x1) x1 = bx
            if (ay < y0) y0 = ay; if (ay > y1) y1 = ay
            if (by < y0) y0 = by; if (by > y1) y1 = by
        }
        if (x1 <= x0) Box(0f, bedX, 0f, bedY, 0f, zMax) else Box(x0, x1, y0, y1, 0f, zMax)
    }

    data class Box(val x0: Float, val x1: Float, val y0: Float, val y1: Float,
                   val z0: Float, val z1: Float) {
        val cx get() = (x0 + x1) / 2f
        val cy get() = (y0 + y1) / 2f
        val cz get() = (z0 + z1) / 2f
        val spanX get() = x1 - x0
        val spanY get() = y1 - y0
        val spanZ get() = z1 - z0
        val extent get() = maxOf(spanX, spanY, spanZ * 1.15f)
        fun size(): String = "${spanX.toInt()} × ${spanY.toInt()} × ${spanZ.toInt()} mm"
    }

    companion object {
        fun parse(bytes: ByteArray): Toolpath {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "SLT2") {
                "not a toolpath stream: ${String(magic, Charsets.US_ASCII)}"
            }
            val nLayers = b.int
            val bedX = b.float; val bedY = b.float; val zMax = b.float; val q = b.float
            require(q > 0f) { "malformed stream: units per mm is $q" }
            val nFeat = b.int
            val names = ArrayList<String>(nFeat)
            repeat(nFeat) {
                val len = b.get().toInt() and 0xFF
                val raw = ByteArray(len).also { b.get(it) }
                names.add(String(raw, Charsets.UTF_8))
                b.position(b.position() + (31 - len))
            }
            val layers = ArrayList<Layer>(nLayers)
            repeat(nLayers) {
                val z = b.float
                val n = b.int
                val seg = FloatArray(n * 4)
                for (i in 0 until n) {
                    seg[i * 4] = (b.short.toInt() and 0xFFFF) / q
                    seg[i * 4 + 1] = (b.short.toInt() and 0xFFFF) / q
                    seg[i * 4 + 2] = (b.short.toInt() and 0xFFFF) / q
                    seg[i * 4 + 3] = (b.short.toInt() and 0xFFFF) / q
                }
                val feat = ByteArray(n).also { b.get(it) }
                layers.add(Layer(z, seg, feat))
            }
            return Toolpath(bedX, bedY, zMax, names, layers)
        }
    }
}
