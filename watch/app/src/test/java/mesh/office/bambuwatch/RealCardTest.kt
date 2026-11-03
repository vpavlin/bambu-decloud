package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The SD-card listing against the real printer, using the same client the app uses.
 *
 * Opt-in: BAMBU_CODE_FILE=/path/to/code ./build.sh testDebugUnitTest --tests '*RealCardTest*'
 */
class RealCardTest {

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun listsTheCardOverFtps() {
        val code = System.getenv("BAMBU_CODE_FILE")
            ?.let { java.io.File(it).readText().trim() }?.takeIf { it.isNotEmpty() }
        assumeTrue("set BAMBU_CODE_FILE to run", code != null)

        val found = mutableListOf<Triple<String, String, Long>>()
        Ftps("192.168.1.50", 990, "bblp", code!!, trust).use { c ->
            c.connect()
            for (dir in listOf("/cache", "/")) {
                val listing = c.list(dir)
                assertTrue("listing $dir failed: ${listing.error}", listing.error == null)
                for (f in listing.files) {
                    if (CardFile.printable(f.name)) {
                        found += Triple(f.name, if (dir == "/") "/${f.name}" else f.name, f.size)
                    }
                }
            }
        }
        println("  printable files: ${found.size}")
        found.sortedByDescending { it.third }.take(5).forEach { (n, p, s) ->
            println("    $n  ${CardFile.humanSize(s)}  path=$p  → ${CardFile.printPath(CardFile(n, p, s, 0))}")
        }
        assertTrue("no printable files listed — the listing or the parsing is wrong", found.isNotEmpty())
        assertTrue("a 3mf must be printed through its inner gcode",
            found.filter { it.first.lowercase().endsWith(".3mf") }
                .all { CardFile.printPath(CardFile(it.first, it.second, it.third, 0)) == "Metadata/plate_1.gcode" })
    }
}

/**
 * The exact sequence the app performs for Václav's phone: the configured address is the mesh name,
 * which carries the control connection but not FTP's random data port, so the listing must come
 * from the address the printer reports about itself.
 */
class RealCardFallbackTest {

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun countAt(host: String, code: String): Int = Ftps(host, 990, "bblp", code, trust).use { c ->
        c.connect()
        c.list("/cache").files.count { CardFile.printable(it.name) }
    }

    @Test
    fun theReportedAddressRescuesAnAddressThatCannotCarryTheDataChannel() {
        val code = System.getenv("BAMBU_CODE_FILE")
            ?.let { java.io.File(it).readText().trim() }?.takeIf { it.isNotEmpty() }
        assumeTrue("set BAMBU_CODE_FILE to run", code != null)

        val configured = "printer.lan.proteus.office.mesh"
        val reported = Reports.parse(
            org.json.JSONObject(javaClass.classLoader!!.getResource("real-a1-report.json")!!.readText()), null).ip
        println("  reported address: $reported")

        val meshCount = runCatching { countAt(configured, code!!) }
            .onFailure { println("  via mesh name: FAILED (${it.javaClass.simpleName}: ${it.message?.take(60)})") }
            .getOrNull() ?: 0
        println("  via mesh name: $meshCount printable files")

        val ipCount = countAt(reported!!, code!!)
        println("  via reported address: $ipCount printable files")

        assertTrue("the reported address must produce a listing", ipCount > 0)
        // The mesh name works THROUGH this client, and did not work through a plain one: Ftps.list
        // retries the data connection against the control connection's own address when the
        // advertised passive address is refused (measured: "502" without that retry). So this
        // asserts the retry, not merely that the printer is reachable.
        assertTrue("the mesh name should work now that the data channel falls back to the control address, " +
            "but got $meshCount", meshCount > 0)
        assertTrue("both routes should agree on what is on the card ($meshCount vs $ipCount)",
            kotlin.math.abs(meshCount - ipCount) <= 2)
    }
}

/** A timeout must not throw away what was already read: that is the change Václav asked for. */
class RealCardStreamingTest {

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun aShortDeadlineStillReturnsWhatItRead() {
        val code = System.getenv("BAMBU_CODE_FILE")
            ?.let { java.io.File(it).readText().trim() }?.takeIf { it.isNotEmpty() }
        assumeTrue("set BAMBU_CODE_FILE to run", code != null)

        var emitted = 0
        val t0 = System.currentTimeMillis()
        val out = Ftps("192.168.1.50", 990, "bblp", code!!, trust).use { c ->
            c.connect()
            c.list("/cache", deadlineMs = 400, emit = { emitted++ })
        }
        val ms = System.currentTimeMillis() - t0
        println("  with a 400 ms deadline: ${out.files.size} files, $emitted emitted, ${ms} ms, error=${out.error}")

        // deliberately far shorter than the listing takes: the point is that it keeps what it got
        assertTrue("a cut-off listing must still return entries (it returned none)", out.files.isNotEmpty())
        assertEquals("every file handed back must have been streamed", out.files.size, emitted)
        assertTrue("the caller must be told it was cut off, not left to assume a complete list",
            out.error?.contains("partial") == true || out.error?.contains("stopped") == true)

        // and the whole thing, for the real numbers
        val full = Ftps("192.168.1.50", 990, "bblp", code, trust).use { c ->
            c.connect(); c.list("/cache")
        }
        println("  full /cache listing: ${full.files.size} files in ${System.currentTimeMillis() - t0} ms total")
        assertTrue("the full listing should be larger than the cut-off one", full.files.size >= out.files.size)
        assertTrue("the full listing should be complete", full.error == null)
    }
}

/** The screen shows the newest 20. That is only honest if the sort is real. */
class RealCardOrderTest {

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun theListingCanBeSortedNewestFirst() {
        val code = System.getenv("BAMBU_CODE_FILE")
            ?.let { java.io.File(it).readText().trim() }?.takeIf { it.isNotEmpty() }
        assumeTrue("set BAMBU_CODE_FILE to run", code != null)

        val files = Ftps("192.168.1.50", 990, "bblp", code!!, trust).use { c ->
            c.connect(); c.list("/cache").files
        }
        val dated = files.filter { it.modified > 0 }
        println("  ${files.size} files, ${dated.size} with a parsed date")
        assertTrue("most of the card must have parseable dates: ${files.size} files, ${dated.size} dated",
            dated.size >= files.size * 8 / 10)

        val sorted = files.sortedWith(
            compareByDescending<CardFile> { it.modified }.thenByDescending { it.name.lowercase() })
        println("  newest five:")
        sorted.take(5).forEach { println("    ${Age.at(it.modified)}  ${it.name.take(60)}") }
        val firstFive = sorted.take(5).map { it.modified }
        assertTrue("the newest five must be in descending order", firstFive == firstFive.sortedDescending())
        assertTrue("the newest file should be recent, not from the dawn of the card",
            sorted.first().modified > 1_600_000_000_000L)
    }
}
