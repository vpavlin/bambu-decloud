package mesh.office.bambuwatch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Files on the printer's SD card, over the FTPS server on 990 with the same user and access code
 * as MQTT.
 *
 * Why this exists: the MQTT command `get_sdcard_file_list` answers `result: success` with no
 * payload, and `gcode_file` is empty in a report from an idle printer — so the card itself is the
 * only reliable source of "what can I print". Measured 23-10: `/` 30 entries, `/cache` 578.
 *
 * TLS without a CA, like the printer's other port: the certificate is pinned per host:port on
 * first use, and a change is an error, not a warning.
 */
class Card(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** the printer's own address from its last report: the card can be reached there when a
     *  mesh name or a forwarded port carries the control connection but drops the data one */
    private val reportedAddress: () -> String? = { null }
) {
    private val _files = MutableStateFlow<List<CardFile>?>(
        CardFile.fromJson(prefs.cardCache).takeIf { it.isNotEmpty() })
    val files: StateFlow<List<CardFile>?> = _files

    /** true while what is shown came from the last visit rather than from the printer */
    private val _fromCache = MutableStateFlow(_files.value != null)
    val fromCache: StateFlow<Boolean> = _fromCache

    /** when the list on screen was read from the printer; 0 when it is the cached copy */
    private val _readAt = MutableStateFlow(prefs.cardCacheAt)
    val readAt: StateFlow<Long> = _readAt

    /** how many files have been read so far, for a "reading… N" line */
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    private val _busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busyFlow

    /**
     * Read the card. `/cache` holds what Bambu Studio has printed or uploaded; the root holds
     * 3mf projects. Both are listed because a job may live in either.
     */
    /**
     * Read the card, showing what has been read so far.
     *
     * `/cache` alone by default: it is where Studio puts printable jobs, and asking for both trees
     * at once is what made a slow data channel look like an empty card. The root folder is a
     * deliberate extra ([includeRoot]) rather than part of every refresh.
     */
    fun refresh(includeRoot: Boolean = false, force: Boolean = true) = scope.launch(Dispatchers.IO) {
        if (!prefs.configured) { _note.value = "no printer configured"; return@launch }
        // Coming back to the screen should not cost 18 seconds of listing: if what is cached is a
        // few minutes old, show it. A pull-down or the Refresh button forces a read.
        val age = System.currentTimeMillis() - prefs.cardCacheAt
        if (!force && _files.value?.isNotEmpty() == true && age in 0 until CACHE_FRESH_MS) {
            _fromCache.value = true
            return@launch
        }
        if (!_busy.compareAndSet(false, true)) return@launch
        _busyFlow.value = true
        _note.value = null

        val configured = prefs.cardHost.ifBlank { prefs.host }
        val hosts = (listOf(configured) + reportedAddress())
            .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val collected = mutableListOf<CardFile>()
        val errors = mutableListOf<String>()
        var used: String? = null

        for ((i, host) in hosts.withIndex()) {
            var seenThisHost = 0
            val attempt = runCatching {
                Ftps(host, CARD_PORT, "bblp", prefs.accessCode, trust("$host:$CARD_PORT")).use { c ->
                    c.connect()
                    val dirs = if (includeRoot) listOf("/cache", "/") else listOf("/cache")
                    for (dir in dirs) {
                        val listing = c.list(dir, emit = { f ->
                            if (CardFile.printable(f.name)) {
                                seenThisHost++
                                collected += f.copy(path = if (dir == "/") "/${f.name}" else f.name)
                                // Count only. The printer lists in its own order — neither oldest
                                // nor newest first — so revealing files as they arrive looks
                                // random, because it is. The finished list is sorted before display.
                                _progress.value = collected.size
                            }
                        })
                        if (listing.error != null) errors += "$host $dir: ${listing.error}"
                    }
                }
                seenThisHost
            }.getOrElse { t ->
                errors += "$host: ${explain(t)}"
                0
            }
            _progress.value = 0
            // a failed host must not empty the list we already had on screen
            if (collected.isNotEmpty()) {
                _files.value = collected.sortedWith(
                    compareByDescending<CardFile> { it.modified }.thenByDescending { it.name.lowercase() })
            }
            if (attempt > 0) { used = host; break }
            if (i < hosts.lastIndex) errors += "trying ${hosts[i + 1]} instead"
        }

        _progress.value = 0
        // never replace a good list with an empty one: a failed read is not an empty card, and
        // the cached copy is what makes returning to this screen instant instead of 18 seconds
        if (collected.isNotEmpty()) {
            _files.value = collected.sortedWith(
                compareByDescending<CardFile> { it.modified }.thenByDescending { it.name.lowercase() })
            _readAt.value = System.currentTimeMillis()
            prefs.cardCacheAt = _readAt.value
            prefs.cardCache = CardFile.toJson(_files.value!!)
        }
        _note.value = when {
            collected.isNotEmpty() && used != null && used != configured -> "read the card at $used:$CARD_PORT (not $configured)"
            collected.isNotEmpty() && errors.isNotEmpty() -> errors.joinToString("; ")
            collected.isNotEmpty() -> null
            errors.isNotEmpty() -> "could not read the card — " + errors.joinToString("; ")
            else -> "the card answered at $configured:$CARD_PORT but has no .gcode or .3mf files"
        }
        _busyFlow.value = false
        _busy.set(false)
    }

    private fun trust(pinKey: String): X509TrustManager {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: error("no certificate")
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(cert.encoded).joinToString("") { "%02x".format(it) }
                val pinned = prefs.pin(pinKey)
                if (pinned == null) prefs.setPin(pinKey, sha)
                else if (pinned != sha) error("certificate changed for $pinKey (pinned ${pinned.take(16)}…, got ${sha.take(16)}…)")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return tm
    }

    private fun explain(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
        return "card: $chain"
    }
}

private const val CARD_PORT = 990

/** how long a cached listing is considered current enough to show without re-reading */
private const val CACHE_FRESH_MS = 10 * 60 * 1000L

data class CardFile(val name: String, val path: String, val size: Long, val modified: Long) {

    companion object {
        /** what the printer can actually be asked to print */
        fun printable(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".gcode") || n.endsWith(".3mf") || n.endsWith(".gcode.3mf")
        }

        /**
         * Bambu's project_file wants the path inside the file for a 3mf, and the plain path for a
         * gcode. Best effort, and if it is wrong the printer says so rather than printing junk.
         */
        fun printPath(f: CardFile): String =
            if (f.path.lowercase().endsWith(".3mf")) "Metadata/plate_1.gcode" else f.path

        fun humanSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }

        /** the last listing, so opening the screen is instant instead of 18 s of nothing */
        fun toJson(files: List<CardFile>): String = org.json.JSONArray().apply {
            files.forEach { f ->
                put(org.json.JSONObject().apply {
                    put("n", f.name); put("p", f.path); put("s", f.size); put("m", f.modified)
                })
            }
        }.toString()

        fun fromJson(text: String?): List<CardFile> = runCatching {
            val arr = org.json.JSONArray(text ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    CardFile(it.optString("n"), it.optString("p"), it.optLong("s", 0), it.optLong("m", 0))
                }
            }
        }.getOrDefault(emptyList())
    }
}
