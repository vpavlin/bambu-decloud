package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class FramesTest {

    private fun jpegLike(pad: Int, tail: Int = 100): ByteArray {
        val b = ByteArray(pad + 2 + tail) { 7 }
        b[pad] = 0xFF.toByte(); b[pad + 1] = 0xD8.toByte()
        return b
    }

    @Test
    fun normalFrameStartsAtZero() {
        assertEquals(0, Frames.findStart(jpegLike(0)))
    }

    @Test
    fun recoversWhenTheHeaderIsCountedInTheLength() {
        // a firmware that includes its own 16-byte header in the declared size leaves
        // 16 bytes in front of the image; the frame must still be found
        assertEquals(16, Frames.findStart(jpegLike(16)))
    }

    @Test
    fun findsItAbitFurtherIn() {
        assertEquals(8, Frames.findStart(jpegLike(8)))
    }

    @Test
    fun givesUpWhenThereIsNoImage() {
        assertEquals(-1, Frames.findStart(ByteArray(200) { 3 }))
    }

    @Test
    fun doesNotRunPastTheEndOnATinyBody() {
        assertEquals(-1, Frames.findStart(ByteArray(1)))
        assertEquals(-1, Frames.findStart(ByteArray(0)))
        // FF at the very last byte must not be read as the start of a pair
        assertEquals(-1, Frames.findStart(byteArrayOf(1, 2, 0xFF.toByte())))
    }

    @Test
    fun windowIsRespected() {
        // marker beyond the search window is not accepted
        assertEquals(-1, Frames.findStart(jpegLike(120), window = 64))
    }
}
