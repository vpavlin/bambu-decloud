package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two bits of FTP that can silently go wrong: the passive address, and the listing line. */
class FtpsParseTest {

    @Test
    fun passiveReplyGivesHostAndPort() {
        val r = "227 Entering Passive Mode (192,168,1,50,203,127)."
        assertEquals("192.168.1.50" to (203 * 256 + 127), Ftps.parsePasv(r))
    }

    @Test
    fun aPassiveReplyWeCannotReadIsNullNotAWrongAddress() {
        assertNull(Ftps.parsePasv("227 Entering Passive Mode"))
        assertNull(Ftps.parsePasv("500 nope"))
    }

    /** the real printer's format, including a name with spaces — the reason to parse properly */
    @Test
    fun aListingLineGivesNameAndSize() {
        assertEquals("Bambu Spool_plate_1.gcode" to 5_616_205L,
            Ftps.parseListLine("-rw-rw-rw-   1 root  root   5616205 Mar 21  2025 Bambu Spool_plate_1.gcode"))
        assertEquals("status-network-earing.gcode.3mf" to 1_786_537L,
            Ftps.parseListLine("-rw-rw-rw-   1 root  root   1786537 May 27  2025 status-network-earing.gcode.3mf"))
    }

    @Test
    fun directoriesAndNoiseAreSkipped() {
        assertNull(Ftps.parseListLine("drw-rw-rw-   1 root  root         0 Feb 11  2025 cache"))
        assertNull(Ftps.parseListLine("total 12"))
        assertNull(Ftps.parseListLine(""))
        assertNull(Ftps.parseListLine("garbage"))
    }

    /** a name that starts like a permission flag must not be mistaken for a directory */
    @Test
    fun anOrdinaryFileIsNotLost() {
        assertEquals("d-thing.3mf" to 42L,
            Ftps.parseListLine("-rw-rw-rw-   1 root  root        42 Oct 23 08:53 d-thing.3mf"))
    }
}

/** The date column decides what counts as "recent", so it is pinned at a fixed moment. */
class FtpsDateTest {

    private val now = java.time.ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))

    @Test
    fun aYearIsReadWhenTheListingHasOne() {
        val t = Ftps.parseListDate("-rw-rw-rw-   1 root  root   1786537 May 27  2025 status.gcode.3mf", now)
        assertEquals(java.time.LocalDate.of(2025, 5, 27), java.time.Instant.ofEpochMilli(t!!).atZone(now.zone).toLocalDate())
    }

    @Test
    fun aTimeMeansThisYear() {
        val t = Ftps.parseListDate("-rw-rw-rw-   1 root  root        42 Oct 23 08:53 thing.3mf", now)
        val at = java.time.Instant.ofEpochMilli(t!!).atZone(now.zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 23), at.toLocalDate())
        assertEquals(8, at.hour)
        assertEquals(53, at.minute)
    }

    @Test
    fun rubbishGivesNoDateRatherThanAWrongOne() {
        org.junit.Assert.assertNull(Ftps.parseListDate("drw-rw-rw- 1 root root 0 Jan 01 1980 cache", now))
        org.junit.Assert.assertNull(Ftps.parseListDate("total 12", now))
        org.junit.Assert.assertNull(Ftps.parseListDate("garbage", now))
    }
}

/** A connect that never returns is the shape of "the app hung"; this pins that it cannot. */
class FtpsTimeoutTest {

    @Test
    fun connectingToASilentAddressFailsFastAndSaysSo() {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        // 10.255.255.1 is reserved space: no answer, no RST, so a socket without a timeout would
        // sit there for minutes
        val started = System.currentTimeMillis()
        val err = runCatching { Ftps("10.255.255.1", 990, "bblp", "x", trust).use { it.connect(timeoutMs = 1_200) } }
            .exceptionOrNull()
        val ms = System.currentTimeMillis() - started
        println("  silent host: failed after $ms ms with ${err?.message?.take(90)}")
        org.junit.Assert.assertNotNull("connecting to a silent host must fail, not hang", err)
        org.junit.Assert.assertTrue("must give up in about the timeout, not minutes (took $ms ms)", ms < 6_000)
        org.junit.Assert.assertTrue("the message must name the phase and the address: ${err?.message}",
            err!!.message!!.contains("connect") && err.message!!.contains("10.255.255.1"))
    }
}
