package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame length is little-endian. Reading it big-endian makes a 40 KB frame look like
 * ~2.4 billion bytes, so the reader rejects every frame and the failure looks like a
 * protocol mismatch rather than an endian mistake.
 */
class FrameHeaderTest {

    private fun header(size: Int, word8: Int = 1): ByteArray {
        val b = ByteArray(16)
        b[0] = size.toByte(); b[1] = (size ushr 8).toByte()
        b[2] = (size ushr 16).toByte(); b[3] = (size ushr 24).toByte()
        b[8] = word8.toByte(); b[9] = (word8 ushr 8).toByte()
        b[10] = (word8 ushr 16).toByte(); b[11] = (word8 ushr 24).toByte()
        return b
    }

    @Test
    fun sizeIsLittleEndian() {
        // a realistic 40960-byte jpeg: 0x0000A000
        val p = FrameHeader.parse(header(40960))
        assertEquals(40960, p.size)
        assertTrue(p.plausible)
    }

    @Test
    fun wouldBeAbsurdIfReadBigEndian() {
        val b = header(40960)
        val bigEndian = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        assertFalse("the big-endian reading must be rejected as implausible",
            FrameHeader.parse(header(40960)).copy(size = bigEndian).plausible)
    }

    @Test
    fun word8IsOneOnARealFrame() {
        assertTrue(FrameHeader.parse(header(1000, 1)).looksLikeAHeader)
        assertFalse(FrameHeader.parse(header(1000, 0)).looksLikeAHeader)
    }

    @Test
    fun zeroAndHugeSizesAreNotPlausible() {
        assertFalse(FrameHeader.parse(header(0)).plausible)
        assertFalse(FrameHeader.parse(header(-1)).plausible)
    }
}
