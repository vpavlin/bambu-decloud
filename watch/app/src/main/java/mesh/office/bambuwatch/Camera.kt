package mesh.office.bambuwatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

/**
 * Camera for P1/A1 printers: TLS to 6000, an 80-byte auth packet, then a stream of
 * length-prefixed JPEGs. There is no HTTP and no RTSP on that port.
 *
 * auth packet, big endian:
 *   [0..3]   payload length = 80
 *   [4..7]   username length = 4
 *   [8..11]  "bblp"
 *   [12..15] access code length
 *   [16..]   access code, the remainder of the 80 bytes zero
 *
 * frame: 16-byte header, big-endian length in the first 4 bytes, then that many bytes
 * of JPEG.
 *
 * The framing has been seen to vary between firmware versions, and this has never run
 * against real hardware, so the reader is deliberately tolerant instead of strict:
 *   - a length that is plausible but 16 too large (a firmware counting its own header)
 *     is handled by scanning for the JPEG start-of-image
 *   - anything that does not decode is counted and reported, never silently dropped
 * The diagnostic line exists so that a single run on a real printer says WHAT was wrong.
 */
class Camera(
    private val prefs: Prefs,
    private val scope: CoroutineScope
) {
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    /** short human line: frames decoded, last size, and anything that looked wrong */
    private val _diag = MutableStateFlow<String?>(null)
    val diag: StateFlow<String?> = _diag

    private var job: Job? = null
    @Volatile private var layout = CameraAuth.Layout.STANDARD

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                attempt++
                // attempt 1 with the documented packet, attempt 2 with the 0.6 layout, then
                // the documented one again: an A1 closes the socket on a bad packet, which
                // looks identical to a bad access code, so try both rather than spend a run
                layout = if (attempt == 2) CameraAuth.Layout.LEGACY else CameraAuth.Layout.STANDARD
                try {
                    stream()
                } catch (t: Throwable) {
                    _note.value = describe(t)
                    _frame.value = null
                    if (attempt > 1) _diag.value = "no frames (attempt $attempt)"
                }
                kotlinx.coroutines.delay(if (attempt < 3) 2000 else 8000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _frame.value = null
        _diag.value = null
    }

    private fun stream() {
        val host = prefs.host
        val port = prefs.camPort
        val t0 = SystemClock.elapsedRealtime()
        // reported to the user on failure: "stopped at <stage> after <n>ms"
        var stage = "tcp connect"
        fun elapsed() = SystemClock.elapsedRealtime() - t0
        try { streamStages(host, port, t0, { stage = it }, ::elapsed) }
        catch (t: Throwable) {
            throw Stage(stage, elapsed(), t)
        }
    }

    private class Stage(val name: String, val ms: Long, val err: Throwable) :
        Exception("stage $name after ${ms}ms", err)

    /** the printer closed mid-read: keep what it did send, it may be an error or a hello */
    private class EarlyClose(val got: Int, val bytes: ByteArray) :
        Exception("closed after $got bytes")

    private fun readFully(din: DataInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = din.read(buf, off, buf.size - off)
            if (n < 0) throw EarlyClose(off, buf.copyOf(off))
            off += n
        }
    }

    private fun streamStages(
        host: String,
        port: Int,
        t0: Long,
        mark: (String) -> Unit,
        elapsed: () -> Long
    ) {
        // same leaf certificate as the MQTT port; the pin is keyed on the MQTT pair
        val factory = Tls.factory(prefs, "$host:${prefs.mqttPort}", onCert = { cert ->
            Tls.cnOf(cert)?.let { cn -> if (prefs.serial.isBlank()) prefs.serial = cn }
        })
        val socket = factory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), 8000)
        // an SSLSocket handshakes lazily on first IO; force it so the TLS stage is real
        mark("tls handshake")
        socket.startHandshake()
        socket.soTimeout = 15000
        socket.use { s ->
            val out = s.getOutputStream()
            out.write(CameraAuth.packet(layout, prefs.accessCode))
            out.flush()
            mark("auth sent (layout ${layout.name.lowercase()}), waiting for the first frame")

            val din = DataInputStream(s.getInputStream())
            _note.value = null
            var frames = 0
            var bad = 0
            var lastSize = 0
            var sawOddLength = false

            while (job?.isActive == true) {
                val header = ByteArray(16)
                readFully(din, header)
                val hdr = FrameHeader.parse(header)
                val declared = hdr.size
                if (!hdr.plausible) {
                    throw IllegalStateException(
                        "implausible frame length $declared (word8=${hdr.word8}, header ${hex(header)})"
                    )
                }
                mark("streaming")
                val body = ByteArray(declared)
                readFully(din, body)
                lastSize = declared

                // tolerant: find the start-of-image rather than trusting offset 0
                val start = Frames.findStart(body)
                if (start < 0) { bad++; continue }
                if (start != 0) sawOddLength = true

                val bmp = BitmapFactory.decodeByteArray(body, start, body.size - start)
                if (bmp != null) {
                    frames++
                    _frame.value = bmp
                } else {
                    bad++
                }

                if (frames % 10 == 1 || bad > 0) {
                    _diag.value = buildString {
                        append("frame ").append(frames)
                        append(" · ").append(lastSize / 1024).append(" KB")
                        if (bad > 0) append(" · ").append(bad).append(" undecodable")
                        if (sawOddLength) append(" · offset start")
                        append(" · auth ").append(layout.name.lowercase())
                    }
                }
            }
        }
    }

    /**
     * Class names are always included: SocketTimeoutException and ConnectException both
     * carry a message, so matching on the message alone (as an earlier version did) never
     * saw the type and fell through to the raw text.
     *
     * The wording separates the cases that matter to a user: "timed out" is traffic that
     * went nowhere (a wrong address, or one this device has no route to), while "refused"
     * means something answered and said no.
     */
    private fun describe(t: Throwable): String {
        val where = "${prefs.host}:${prefs.camPort}"
        val st = t as? Stage
        val root = st?.err ?: t
        (root as? EarlyClose)?.let { ec ->
            return Errors.describeEarlyClose(where, st?.name ?: "unknown stage", st?.ms ?: 0L,
                ec.got, ec.bytes.copyOf(minOf(ec.bytes.size, 32)))
        }
        val types = generateSequence(root) { it.cause }.map { it.javaClass.simpleName }.toList()
        val chain = generateSequence(root) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
        return Errors.describeCamera(where, st?.name ?: "unknown stage", st?.ms ?: 0L, types, chain)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }


}
