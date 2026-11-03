package mesh.office.bambuwatch

/**
 * The auth packet the P1/A1 camera expects on port 6000, and the MQTT topics.
 *
 * Both are pure so they can be tested byte for byte: the camera packet in 0.6 was wrong
 * in every field (big-endian lengths, username at offset 8, code at 16), and an A1 answers
 * a rejected packet by closing the socket — which is indistinguishable from a wrong access
 * code or a network problem unless the bytes are right to begin with.
 */
object CameraAuth {

    enum class Layout { STANDARD, LEGACY }

    /**
     * Documented layout, 80 bytes, LITTLE-endian:
     *   [0:4]   u32 0x40      (the length of the rest)
     *   [4:8]   u32 0x3000
     *   [8:16]  two zero u32
     *   [16:48] username, NUL padded to 32
     *   [48:80] access code, NUL padded to 32
     */
    fun standard(code: String, user: String = "bblp"): ByteArray {
        val b = ByteArray(80)
        putLE(b, 0, 0x40)
        putLE(b, 4, 0x3000)
        putLE(b, 8, 0)
        putLE(b, 12, 0)
        putString(b, 16, user, 32)
        putString(b, 48, code, 32)
        return b
    }

    /**
     * The layout shipped up to 0.6, kept ONLY as a fallback attempt: big-endian lengths,
     * username length, code length. It is here so one run on the printer can try both
     * rather than costing another round trip.
     */
    fun legacy(code: String, user: String = "bblp"): ByteArray {
        val b = ByteArray(80)
        putBE(b, 0, 80)
        putBE(b, 4, user.length)
        putString(b, 8, user, 24)
        putBE(b, 12, code.length)
        putString(b, 16, code, 64)
        return b
    }

    fun packet(layout: Layout, code: String, user: String = "bblp"): ByteArray = when (layout) {
        Layout.STANDARD -> standard(code, user)
        Layout.LEGACY -> legacy(code, user)
    }

    private fun putLE(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
        b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
    }

    private fun putBE(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun putString(b: ByteArray, at: Int, s: String, width: Int) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(b, at, 0, minOf(bytes.size, width))
    }
}

/**
 * A printer's broker is expected to accept only its own device topics; subscribing to
 * `device/+/report` is what a client does when it does not know the serial. Since the serial
 * is in the certificate, there is no need to guess — the wildcard is a last resort.
 */
object Topics {
    const val WILDCARD_REPORT = "device/+/report"

    fun report(serial: String): String {
        require(serial.isNotBlank()) { "serial required" }
        return "device/$serial/report"
    }

    fun request(serial: String): String {
        require(serial.isNotBlank()) { "serial required" }
        return "device/$serial/request"
    }

    fun reportFor(serial: String?): String =
        if (serial.isNullOrBlank()) WILDCARD_REPORT else report(serial)

    fun requestFor(serial: String?): String? =
        if (serial.isNullOrBlank()) null else request(serial)

    val PUSH_ALL = """{"pushing":{"sequence_id":"0","command":"pushall"}}"""
}
