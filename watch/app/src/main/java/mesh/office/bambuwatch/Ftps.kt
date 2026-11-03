package mesh.office.bambuwatch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Just enough FTPS for one job: list the printer's SD card.
 *
 * Written by hand instead of using commons-net, because the printer answers `331 ` with an empty
 * message and commons-net rejects that as a truncated reply. Six commands are all this needs, and
 * doing them directly means the parsing is ours, testable, and cannot be broken by a library
 * deciding the server is rude.
 *
 * Implicit TLS (the socket is TLS from the first byte), then: greet, USER, PASS, PBSZ 0, PROT P,
 * and for each listing: PASV, LIST, read the data channel, expect 226.
 */
class Ftps(
    private val host: String,
    private val port: Int = 990,
    private val user: String = "bblp",
    private val password: String,
    private val trust: X509TrustManager
) : AutoCloseable {

    private var control: SSLSocket? = null
    private var reader: BufferedReader? = null

    /** where a failure happened, so the screen can say "connect" or "login" instead of "timeout" */
    var phase: String = "connect"
        private set

    fun connect(timeoutMs: Int = 8_000) {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }

        // A connect with no explicit timeout blocks for the OS default (minutes) when the address
        // does not answer — which is indistinguishable from "the app hung". So: connect a plain
        // socket with our own timeout, then layer TLS over it.
        phase = "connect"
        val plain = java.net.Socket()
        try {
            plain.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        } catch (t: Throwable) {
            plain.close()
            throw java.io.IOException("connect to $host:$port failed within ${timeoutMs} ms — " +
                (t.message ?: t.javaClass.simpleName), t)
        }
        plain.soTimeout = timeoutMs
        phase = "TLS handshake"
        val s = try {
            (ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also {
                it.soTimeout = timeoutMs
                it.startHandshake()
            }
        } catch (t: Throwable) {
            plain.close()
            throw java.io.IOException("TLS to $host:$port failed — " + (t.message ?: t.javaClass.simpleName), t)
        }
        control = s
        reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))

        phase = "greeting"
        expect(read(), 220)
        phase = "login"
        expect(cmd("USER $user"), 331)
        expect(cmd("PASS $password"), 230)
        phase = "protected data channel"
        expect(cmd("PBSZ 0"), 200)
        expect(cmd("PROT P"), 200)
        phase = "ready"
    }

    /**
     * Names under [dir], streamed: each file is reported as it arrives, so a listing that times
     * out halfway still leaves the caller with what was read instead of nothing.
     *
     * Václav (23-10) gets a timeout on the card. Reading the whole listing as one blob means a
     * slow or dropped data channel discards everything; this hands back what it has and says how
     * far it got.
     */
    fun list(
        dir: String,
        deadlineMs: Int = 60_000,
        emit: ((CardFile) -> Unit)? = null
    ): Listing {
        val pasv = cmd("PASV")
        if (!pasv.startsWith("227")) return Listing(emptyList(), "the printer refused PASV: $pasv")
        val (advHost, advPort) = parsePasv(pasv)
            ?: return Listing(emptyList(), "could not read the passive address from: $pasv")

        // The advertised address is the printer's own view; it is unreachable when the printer is
        // reached through anything that rewrites addresses (measured: 502 through the mesh name).
        val controlHost = control?.inetAddress?.hostAddress ?: advHost
        var lastError: String? = null

        for ((h, p) in listOf(advHost to advPort, controlHost to advPort).distinct()) {
            val data = try {
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }
                val plain = java.net.Socket()
                plain.connect(java.net.InetSocketAddress(h, p), 8_000)
                plain.soTimeout = 8_000
                (ctx.socketFactory.createSocket(plain, h, p, true) as SSLSocket).also { it.startHandshake() }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName} to $h:$p" + (t.message?.let { " — $it" } ?: "")
                continue
            }
            val got = mutableListOf<CardFile>()
            try {
                cmd("LIST $dir")
                val reader = data.inputStream.bufferedReader(Charsets.UTF_8)
                data.soTimeout = 8_000
                val started = System.currentTimeMillis()
                var cutOff = false
                while (true) {
                    if (System.currentTimeMillis() - started > deadlineMs) {
                        lastError = "listing stopped after $deadlineMs ms with ${got.size} entries " +
                            "(the data channel is slow or was cut)"
                        cutOff = true
                        break
                    }
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    val (name, size) = parseListLine(line) ?: continue
                    val f = CardFile(name = name, path = name, size = size,
                        modified = parseListDate(line) ?: 0L)
                    got += f
                    emit?.invoke(f)
                }
                // Only ask for the closing reply when the listing ended on its own. After a
                // cut-off the server is still mid-transfer, and waiting for that reply cost 16 s
                // in a measured run — which is exactly the kind of delay that looks like a hang.
                if (!cutOff) runCatching { if (!read().startsWith("2")) read() }
                return Listing(got, if (got.isEmpty()) lastError else lastError?.let { "partial: $it" })
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName} while listing $dir" + (t.message?.let { " — $it" } ?: "")
            } finally {
                runCatching { data.close() }
            }
            if (got.isNotEmpty()) return Listing(got, lastError?.let { "partial: $it" })
        }
        return Listing(emptyList(), lastError ?: "could not open a data connection")
    }

    override fun close() {
        runCatching { cmd("QUIT") }
        runCatching { control?.close() }
        control = null
    }

    private fun cmd(line: String): String {
        val c = control ?: error("not connected")
        c.outputStream.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        c.outputStream.flush()
        return read()
    }

    private fun read(): String = reader?.readLine()?.trim() ?: error("connection closed")

    private fun expect(reply: String, code: Int) {
        if (!reply.startsWith(code.toString())) error("expected $code, got: $reply")
    }

    /** what a directory listing produced: files, or the reason there are none */
    data class Listing(val files: List<CardFile>, val error: String? = null)

    companion object {
        /** 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2) */
        fun parsePasv(reply: String): Pair<String, Int>? {
            val m = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(reply) ?: return null
            val p = m.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (p.size != 6) return null
            return "${p[0]}.${p[1]}.${p[2]}.${p[3]}" to (p[4] * 256 + p[5])
        }

        /**
         * `-rw-rw-rw- 1 root root 1786537 May 27 2025 name with spaces.3mf`
         * → name and size. Directories and unparseable lines give null.
         */
        private val MONTHS = mapOf(
            "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
            "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12)

        /**
         * The listing's date column: `May 27  2025` for old files, `Oct 23 08:53` for recent ones
         * (no year, meaning this year). Returns epoch millis, or 0 when it cannot be read — a
         * wrong date is worse than none, so nothing is guessed.
         */
        fun parseListDate(line: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Long? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("d") || t.startsWith("total")) return null
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 8) return null
            val month = MONTHS[parts[5]] ?: return null
            val day = parts[6].toIntOrNull() ?: return null
            val yearPart = parts[7]
            return runCatching {
                if (yearPart.contains(":")) {
                    val (h, m) = yearPart.split(":").map { it.toInt() }
                    java.time.ZonedDateTime.of(now.year, month, day, h, m, 0, 0, now.zone)
                } else {
                    java.time.ZonedDateTime.of(yearPart.toInt(), month, day, 0, 0, 0, 0, now.zone)
                }.toInstant().toEpochMilli()
            }.getOrNull()
        }

        fun parseListLine(line: String): Pair<String, Long>? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("total")) return null
            if (t.startsWith("d")) return null                       // a folder, not a file
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 9) return null
            val size = parts[4].toLongOrNull() ?: return null
            val name = parts.drop(8).joinToString(" ")
            if (name.isBlank() || name == "." || name == "..") return null
            return name to size
        }
    }
}
