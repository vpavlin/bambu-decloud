package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    private val box = Toolpath.Box(0f, 20f, 0f, 20f, 0f, 40f)
    private val top = Projection.fit(box, 400f, 400f, 1f, yaw = 0f, pitch = 0.02f)
    private val front = Projection.fit(box, 400f, 400f, 1f, yaw = 0f, pitch = 1.45f)

    @Test fun `looking down, the far side of the bed is higher on screen`() {
        val near = top.project(10f, 0f, 0f)     // y = 0
        val far = top.project(10f, 20f, 0f)     // y = 20
        assertTrue("far should be above near on screen", far[1] < near[1])
        // and height barely moves anything in a top view
        val high = top.project(10f, 10f, 40f)
        val low = top.project(10f, 10f, 0f)
        assertEquals(high[1], low[1], 6f)
    }

    @Test fun `from the front, height is vertical`() {
        val low = front.project(10f, 10f, 0f)
        val high = front.project(10f, 10f, 40f)
        assertTrue("a taller layer must be higher on screen", high[1] < low[1])
        assertTrue("40 mm must be visible", low[1] - high[1] > 20f)
    }

    @Test fun `the part is centred, not the plate`() {
        val p = top.project(10f, 10f, 20f)       // the centre of the box
        assertEquals(200f, p[0], 1f)
        assertEquals(200f, p[1], 1f)
    }

    @Test fun `the matrix agrees with the projection for every layer`() {
        for (yaw in floatArrayOf(-0.7f, 0f, 1.1f)) {
            for (pitch in floatArrayOf(0.05f, 0.95f, 1.45f)) {
                val p = Projection.fit(box, 400f, 300f, 1.3f, yaw, pitch)
                for (z in floatArrayOf(0f, 10f, 40f)) {
                    val m = p.matrix(z)
                    assertEquals("Matrix.setValues needs exactly nine", 9, m.size)
                    assertEquals("bottom row is identity", 1f, m[8], 0f)
                    for ((x, y) in listOf(0f to 0f, 20f to 5f, 7f to 19f)) {
                        // row-major 3x3: x' = m0*x + m1*y + m2, y' = m3*x + m4*y + m5
                        val viaMatrixX = m[0] * x + m[1] * y + m[2]
                        val viaMatrixY = m[3] * x + m[4] * y + m[5]
                        val pt = p.project(x, y, z)
                        assertEquals("x at yaw=$yaw pitch=$pitch z=$z", pt[0], viaMatrixX, 0.01f)
                        assertEquals("y at yaw=$yaw pitch=$pitch z=$z", pt[1], viaMatrixY, 0.01f)
                    }
                }
            }
        }
    }
}
