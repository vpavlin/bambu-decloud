package mesh.office.bambuwatch

import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera for the toolpath view, as pure arithmetic so it can be tested.
 *
 * R = Rx(pitch) * Rz(yaw), camera looks along +Zc:
 *
 * ```
 * Xc =  ct*dx - st*dy
 * Yc =  cp*st*dx + cp*ct*dy + sp*dz
 * sx = s*Xc + w/2        sy = -s*Yc + h/2      (screen y points down)
 * ```
 *
 * `pitch` 0.02 is straight down, 1.52 is a front elevation — and dragging DOWN
 * pulls the camera up over the model, as CAD and Bambu Studio do.
 */
class Projection(
    val width: Float,
    val height: Float,
    val scale: Float,
    val yaw: Float,
    val pitch: Float,
    val cx: Float,
    val cy: Float,
    val cz: Float,
) {
    private val ct = cos(yaw)
    private val st = sin(yaw)
    private val cp = cos(pitch)
    private val sp = sin(pitch)

    /** model millimetres to screen pixels */
    fun project(x: Float, y: Float, z: Float): FloatArray {
        val dx = x - cx
        val dy = y - cy
        val dz = z - cz
        return floatArrayOf(
            scale * (ct * dx - st * dy) + width / 2f,
            -scale * (cp * st * dx + cp * ct * dy + sp * dz) + height / 2f,
        )
    }

    /** the 6 values android.graphics.Matrix wants for a plane at height z */
    fun matrix(z: Float): FloatArray {
        val a = scale * ct
        val c = -scale * st
        val b = -scale * cp * st
        val d = -scale * cp * ct
        val e = width / 2f - (a * cx + c * cy)
        val f = height / 2f - (b * cx + d * cy) - scale * sp * (z - cz)
        // android.graphics.Matrix.setValues wants NINE values, row-major:
        //   row 0: a, c, e      (x' = a*x + c*y + e)
        //   row 1: b, d, f      (y' = b*x + d*y + f)
        //   row 2: 0, 0, 1
        // Six values throw "Array must have exactly 9 elements" the moment the
        // canvas draws, which is a crash on opening a sliced job, not a bad picture.
        return floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)
    }

    companion object {
        fun fit(box: Toolpath.Box, w: Float, h: Float, zoom: Float,
                yaw: Float, pitch: Float): Projection {
            val extent = maxOf(box.extent, 1f)
            val s = (minOf(w, h) / extent) * 0.52f * zoom
            return Projection(w, h, s, yaw, pitch, box.cx, box.cy, box.cz)
        }
    }
}
