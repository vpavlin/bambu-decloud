package mesh.office.bambuwatch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These bytes are the whole handshake. The 0.6 version had every field wrong — big-endian
 * lengths, username at 8, code at 16 — and the printer answers a bad packet by closing the
 * socket, which is indistinguishable from a wrong access code.
 */
class CameraAuthTest {

    private val code = "12345678"

    @Test
    fun packetIsEightyBytes() {
        assertEquals(80, CameraAuth.standard(code).size)
        assertEquals(80, CameraAuth.legacy(code).size)
    }

    @Test
    fun documentedLayoutLittleEndianHeader() {
        val b = CameraAuth.standard(code)
        // [0:4] = 0x40 little-endian, [4:8] = 0x3000 little-endian
        assertArrayEquals(byteArrayOf(0x40, 0, 0, 0), b.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x00, 0x30, 0, 0), b.copyOfRange(4, 8))
        // [8:16] two zero words
        assertArrayEquals(ByteArray(8), b.copyOfRange(8, 16))
    }

    @Test
    fun usernameIsNulPaddedAt16AndCodeAt48() {
        val b = CameraAuth.standard(code)
        assertEquals("bblp", String(b.copyOfRange(16, 20), Charsets.US_ASCII))
        assertArrayEquals("bblp".padEnd(32, '\u0000').toByteArray(Charsets.US_ASCII),
            b.copyOfRange(16, 48))
        assertArrayEquals(code.padEnd(32, '\u0000').toByteArray(Charsets.US_ASCII),
            b.copyOfRange(48, 80))
    }

    @Test
    fun aLongCodeIsTruncatedNotOverflowing() {
        val b = CameraAuth.standard("abcdefghijklmnopqrstuvwxyz0123456789") // 36 chars
        assertEquals(80, b.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz012345", String(b.copyOfRange(48, 80), Charsets.US_ASCII))
    }

    @Test
    fun theLegacyLayoutIsNotMistakenForTheCorrectOne() {
        assertNotEquals(CameraAuth.standard(code).toList(), CameraAuth.legacy(code).toList())
        // legacy was big-endian, so 80 lands in the LAST byte of the first word,
        // while the documented packet has 0x40 in the first byte
        assertEquals(0x00, CameraAuth.legacy(code)[0].toInt() and 0xFF)
        assertEquals(0x50, CameraAuth.legacy(code)[3].toInt() and 0xFF)
        assertEquals(0x40, CameraAuth.standard(code)[0].toInt() and 0xFF)
    }

    @Test
    fun packetDispatchFollowsLayout() {
        assertArrayEquals(CameraAuth.standard(code), CameraAuth.packet(CameraAuth.Layout.STANDARD, code))
        assertArrayEquals(CameraAuth.legacy(code), CameraAuth.packet(CameraAuth.Layout.LEGACY, code))
    }
}

/**
 * The printer's broker is expected to drop a client subscribed to device/+/report, so the
 * serial — which comes free in the certificate — must be used whenever it is known.
 */
class TopicsTest {

    @Test
    fun ownTopicsUseTheSerial() {
        assertEquals("device/YOUR_SERIAL/report", Topics.report("YOUR_SERIAL"))
        assertEquals("device/YOUR_SERIAL/request", Topics.request("YOUR_SERIAL"))
    }

    @Test
    fun wildcardOnlyWhenTheSerialIsUnknown() {
        assertEquals(Topics.WILDCARD_REPORT, Topics.reportFor(null))
        assertEquals(Topics.WILDCARD_REPORT, Topics.reportFor(""))
        assertEquals("device/S1/report", Topics.reportFor("S1"))
        assertFalse(Topics.reportFor("S1").contains("+"))
    }

    @Test
    fun noRequestTopicWithoutASerial() {
        assertNull("publishing to device//request would be wrong", Topics.requestFor(null))
        assertNull(Topics.requestFor("  "))
        assertEquals("device/S1/request", Topics.requestFor("S1"))
    }

    @Test
    fun pushAllIsTheDocumentedShape() {
        assertTrue(Topics.PUSH_ALL.contains("\"pushall\""))
        assertTrue(Topics.PUSH_ALL.contains("\"pushing\""))
    }
}
