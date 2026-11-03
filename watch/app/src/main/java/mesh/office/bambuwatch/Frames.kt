package mesh.office.bambuwatch

/**
 * The camera framing is the least certain part of this app: it has been described
 * slightly differently by different sources, and it has never run against real
 * hardware. Rather than trusting that the JPEG begins exactly at offset 0, find the
 * start-of-image marker within a small window. If a firmware counts its own 16-byte
 * header in the declared length, the frame is still recoverable.
 */
/**
 * The 16-byte header that precedes each JPEG.
 *
 * Reference (ha-bambulab, and OpenBambuAPI): the size is LITTLE endian and covers the JPEG
 * only, not the header itself:
 *   [0:4]  little-endian payload size
 *   [4:8]  0
 *   [8:12] 1
 *   [12:16] 0
 * Reading it big-endian turns a 40 KB frame into ~2.4 billion and the reader rejects it as
 * implausible — which is a self-inflicted failure that looks like a protocol mismatch.
 */
object FrameHeader {
    data class Parsed(val size: Int, val word8: Int) {
        val plausible: Boolean get() = size in 1..(8 * 1024 * 1024)
        val looksLikeAHeader: Boolean get() = word8 == 1
    }

    fun parse(b: ByteArray): Parsed {
        require(b.size >= 16) { "header is 16 bytes" }
        fun le(at: Int) = (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
        return Parsed(le(0), le(8))
    }
}

object Frames {
    private const val SOI0 = 0xFF.toByte()
    private const val SOI1 = 0xD8.toByte()

    /** @return offset of FF D8 within the first [window] bytes, or -1 */
    fun findStart(body: ByteArray, window: Int = 64): Int {
        if (body.size < 2) return -1
        val limit = minOf(body.size - 1, window)
        for (i in 0 until limit) {
            if (body[i] == SOI0 && body[i + 1] == SOI1) return i
        }
        return -1
    }
}
