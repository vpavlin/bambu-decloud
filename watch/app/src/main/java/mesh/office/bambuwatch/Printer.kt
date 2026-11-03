package mesh.office.bambuwatch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MQTT-over-TLS client for the printer's local reporting channel. No cloud.
 *
 * connect ssl://<host>:8883 as user "bblp" / access code, subscribe to
 * device/<serial>/report, publish pushall once, then merge the partial updates.
 */
class Printer(
    private val prefs: Prefs,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PrinterState())
    val state: StateFlow<PrinterState> = _state

    /** what we asked for and what came back, newest first: the screen shows this, unfiltered */
    private val _journal = MutableStateFlow<List<String>>(emptyList())
    val journal: StateFlow<List<String>> = _journal

    fun clearJournal() { _journal.value = emptyList() }

    private fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _journal.value = (listOf("$t  $line") + _journal.value).take(6)
    }

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    /** say something on the monitor from outside the connection loop */
    fun setNote(text: String?) { _note.value = text }

    /** how many reports have arrived; if this stays 0 the fault is delivery, not parsing */
    private val _reports = MutableStateFlow(0)
    val reportsSeen: StateFlow<Int> = _reports

    private val _history = MutableStateFlow<List<JobRecord>>(emptyList())
    val history: StateFlow<List<JobRecord>> = _history

    private val inbox = Channel<Pair<String, String>>(Channel.UNLIMITED)
    /** connectComplete fires on Paho's own thread; blocking calls there do not work, so it only signals. */
    private val connectEvents = Channel<MqttClient>(Channel.CONFLATED)
    private var subscriber: Job? = null
    private var client: MqttClient? = null
    private var merged: JSONObject = JSONObject()
    private var collector: Job? = null
    private val running = AtomicBoolean(false)
    /** what the user wants; differs from [running] while a retry is pending or the app is backgrounded */
    private var wantConnected = false
    private var attempt = 0
    private var retry: Job? = null
    private var lastPersist = 0L

    init {
        restoreCached()
        _history.value = History.fromJson(prefs.history)
    }

    /** open with the last thing the printer said, stamped, rather than an empty screen */
    private fun restoreCached() {
        val json = prefs.lastReport ?: return
        val at = prefs.lastReportAt
        runCatching {
            merged = JSONObject(json)
            val p = Reports.parse(merged, prefs.serial.ifBlank { null })
            _state.value = p.copy(connected = false, asOf = at.takeIf { it > 0 })
        }.onFailure { prefs.lastReport = null }
    }

    /** Android took the network away while we were in the background; connect again on return */
    fun onForeground() {
        wantConnected = true
        retry?.cancel(); retry = null
        when (ConnectionPolicy.onStart(prefs.configured, running.get(), _state.value.connected)) {
            ConnectionPolicy.Action.NOTHING -> {}
            ConnectionPolicy.Action.CONNECT -> connect()
            ConnectionPolicy.Action.RECONNECT -> reconnect()
            ConnectionPolicy.Action.DISCONNECT -> dropConnection(keepState = true)
        }
    }

    /**
     * Leaving the foreground: close deliberately rather than let the OS kill the socket.
     * The printer serves only a few clients, so a socket left dangling here is a client slot
     * lost there — and the last state is kept, with its timestamp, for the screen on return.
     */
    fun onBackground() {
        wantConnected = false
        retry?.cancel(); retry = null
        persist(force = true)
        dropConnection(keepState = true)
    }

    private val serial: String get() = prefs.serial

    fun connect() {
        if (running.getAndSet(true)) return
        collector = scope.launch(Dispatchers.Default) { consume() }
        subscriber = scope.launch(Dispatchers.IO) {
            for (c in connectEvents) {
                runCatching { doSubscribe(c) }.onFailure { _note.value = explain(it) }
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { connectBlocking() }
                .onSuccess { attempt = 0 }
                .onFailure { t ->
                    running.set(false)
                    _state.value = _state.value.copy(
                        connected = false,
                        statusWord = ConnectionPolicy.staleWord(_state.value.asOf != null)
                    )
                    if (wantConnected) scheduleRetry(t) else _note.value = explain(t)
                }
        }
    }

    private fun scheduleRetry(t: Throwable) {
        val plan = ConnectionPolicy.onFailure(attempt++, wantConnected)
        if (plan == null) { _note.value = explain(t); return }
        val why = explain(t)
        _note.value = if (plan.giveUp)
            "$why — still failing after several attempts; check the address and the access code in Settings"
        else
            "$why — retrying in ${plan.waitMs / 1000}s"
        retry?.cancel()
        retry = scope.launch(Dispatchers.IO) {
            delay(plan.waitMs)
            if (wantConnected) { running.set(false); connect() }
        }
    }

    private fun reconnect() {
        dropConnection(keepState = true)
        connect()
    }

    private fun dropConnection(keepState: Boolean) {
        running.set(false)
        collector?.cancel(); collector = null
        subscriber?.cancel(); subscriber = null
        runCatching { client?.disconnectForcibly(500, 500, false); client?.close() }
        client = null
        merged = JSONObject()
        _reports.value = 0
        if (!keepState) _state.value = PrinterState()
    }

    /** throttled: a report arrives every few seconds and the store is encrypted, so not every one */
    private fun persist(force: Boolean = false) {
        if (merged.length() == 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 30_000) return
        lastPersist = now
        prefs.lastReport = merged.toString()
        prefs.lastReportAt = _state.value.asOf ?: now
    }

    fun disconnect() {
        wantConnected = false
        retry?.cancel(); retry = null
        dropConnection(keepState = false)
    }

    /** re-ask for a full state without reconnecting */
    fun refresh() = scope.launch(Dispatchers.IO) {
        val s = serial.ifBlank { _state.value.serial ?: "" }
        if (s.isBlank()) { requestWildcardHint(); return@launch }
        runCatching {
            client?.let { send(it, Topics.request(s), Commands.PUSH_ALL) }
        }.onFailure { _note.value = explain(it) }
    }

    private fun requestWildcardHint() {
        _note.value = "waiting for the first report to learn the serial…"
    }

    private fun pushall() = MqttMessage(Commands.PUSH_ALL.toByteArray()).apply { qos = 0 }

    /**
     * Every publish goes through here. In read-only mode anything that is not a plain
     * pushing/pushall is refused, so a control added elsewhere cannot slip out unnoticed.
     */
    private fun send(c: MqttClient, topic: String, payload: String): Boolean {
        Commands.refuseIfReadOnly(prefs.readOnly, payload)?.let {
            _note.value = it
            return false
        }
        c.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 0 })
        log("sent ${Commands.describe(payload)}")
        return true
    }

    private val seq = java.util.concurrent.atomic.AtomicInteger(1)
    private fun nextSeq() = seq.getAndIncrement().toString()

    /** each returns false when refused (read-only) or when the publish fails */
    fun pause(): Boolean = control(Commands.pause(nextSeq()))

    /** print a file that is on the card; same rules as every other command */
    fun printCardFile(f: CardFile, amsSlot: Int? = null): Boolean {
        val c = client
        if (c == null) {
            log("cannot print: not connected")
            _note.value = "cannot print: not connected to the printer"
            return false
        }
        val topic = runCatching { Topics.request(serial.ifBlank { _state.value.serial ?: "" }) }.getOrNull()
        if (topic == null) {
            log("cannot print: serial unknown")
            _note.value = "cannot print: the printer's serial is unknown"
            return false
        }

        // Two spellings of the same request, because two sources disagree and only one of them can
        // be right. The second attempt only happens if the printer is still idle after the first —
        // a started print is never restarted.
        val attempts = Commands.printCandidates(nextSeq(), f, amsSlot = amsSlot)
        scope.launch(Dispatchers.IO) {
            for ((i, payload) in attempts.withIndex()) {
                if (i > 0) {
                    delay(9_000)
                    val g = _state.value.gcodeState?.uppercase()
                    if (g == "RUNNING" || g == "PREPARE") { log("started after attempt $i"); return@launch }
                    log("attempt ${i + 1} did nothing (printer still $g), trying form ${i + 2} of ${attempts.size}")
                }
                val ok = runCatching { send(c, topic, payload) }
                    .onFailure { log("send failed: ${explain(it)}") }
                    .getOrDefault(false)
                if (!ok) { _note.value = "the print request did not leave the phone"; return@launch }
                _note.value = "sent ${Commands.describe(payload)} (${i + 1}/${attempts.size}) — waiting for the printer"
            }
            delay(9_000)
            val g = _state.value.gcodeState?.uppercase()
            if (g != "RUNNING" && g != "PREPARE") {
                log("gave up: printer still reports ${g ?: "no state"}")
                _note.value = "the printer never started it: it still reports ${g ?: "no state"}. " +
                    "Everything sent is listed below."
            } else {
                log("started")
                _note.value = null
            }
        }
        return true
    }

    /** reprint a job we have a file path for; refused while read-only, like every command */
    fun reprint(job: JobRecord): Boolean {
        val file = job.file
        if (file.isNullOrBlank()) {
            _note.value = "the printer never reported that job's file path, so it cannot be reprinted"
            return false
        }
        return control(Commands.reprint(nextSeq(), file, job.name))
    }
    fun resume(): Boolean = control(Commands.resume(nextSeq()))
    fun stop(): Boolean = control(Commands.stop(nextSeq()))

    /** pause/resume/stop; returns false and explains itself while read-only is on */
    fun control(payload: String): Boolean {
        val c = client ?: return false
        val s = serial.ifBlank { _state.value.serial ?: "" }
        if (s.isBlank()) { _note.value = "serial unknown — cannot address the printer"; return false }
        return runCatching { send(c, Topics.request(s), payload) }
            .onFailure { _note.value = explain(it) }
            .getOrDefault(false)
    }

    private fun connectBlocking() {
        // a reconnect must not leave the previous client's socket behind: the printer serves only
        // a few clients, so a half-dead socket here is a slot lost there
        val previous = client
        client = null
        runCatching { previous?.disconnectForcibly(200, 200, false); previous?.close() }

        val host = prefs.host
        val port = prefs.mqttPort
        val c = MqttClient("ssl://$host:$port", "bambuwatch${System.currentTimeMillis() % 100000}", MemoryPersistence())

        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // do NOT subscribe here: Paho runs this on its own thread and blocking calls
                // (subscribe waits for SUBACK, publish waits for PUBACK) misbehave from it —
                // measured: subscribing here receives no reports at all, while the same calls
                // from a normal thread get them immediately.
                // carry the client explicitly: connectComplete runs INSIDE c.connect(), i.e.
                // before the `client = c` assignment below, so reading the field here yields
                // null and every subscribe is silently skipped.
                connectEvents.trySend(c)
            }

            override fun connectionLost(cause: Throwable?) {
                // claim the running flag atomically: it tells us whether this drop is news or
                // the consequence of our own reconnect closing the old client
                val wasRunning = running.getAndSet(false)
                _state.value = _state.value.copy(
                    connected = false,
                    statusWord = ConnectionPolicy.staleWord(_state.value.asOf != null))

                // Android took the network away, or the printer went quiet: not a fault to report
                if (!ConnectionPolicy.onLost(wantConnected, wasRunning)) return

                // recover by ourselves, with backoff: Paho's automatic reconnect was measured in
                // a live test not to bring the session back at all after the broker returned
                scheduleRetry(cause ?: RuntimeException("connection lost"))
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val body = message?.payload?.toString(Charsets.UTF_8) ?: return
                inbox.trySend((topic ?: "") to body)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val opts = MqttConnectOptions().apply {
            // NOT Paho's automatic reconnect: in a live test against a broker that was killed and
            // restarted, the client did not come back within 90 s, and its retry thread kept the
            // process alive afterwards. Our own path — a fresh client, rebuilt from settings, with
            // the backoff in ConnectionPolicy — is already proven, so use it instead.
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            userName = "bblp"
            password = prefs.accessCode.toCharArray()
            // Paho verifies the server hostname against the certificate by default. The
            // printer's certificate is self-signed with NO subject alternative names — only a
            // CN holding the serial — so that check fails with
            // "certificate_unknown: No subject alternative names present", even though our own
            // pinning already accepted the exact certificate. Pinning is the stronger check
            // here: it is a specific leaf, not a name. This is why the camera (our own socket)
            // worked while MQTT did not.
            isHttpsHostnameVerificationEnabled = false
            socketFactory = Tls.factory(prefs, "$host:$port", onCert = { cert ->
                Tls.cnOf(cert)?.let { cn -> if (prefs.serial.isBlank()) prefs.serial = cn }
            })
        }
        client = c
        c.connect(opts)
        _note.value = null
    }

    /** runs off Paho's callback thread: subscribe to the printer's own topic, then ask for state. */
    private fun doSubscribe(c: MqttClient) {
        val known = serial.ifBlank { _state.value.serial }
        val topic = Topics.reportFor(known)
        c.subscribe(topic, 1)
        if (topic == Topics.WILDCARD_REPORT) {
            _note.value = "serial unknown — subscribing to a wildcard, which the printer may reject"
        }
        Topics.requestFor(known)?.let { send(c, it, Commands.PUSH_ALL) }
        _note.value = null
    }

    /** consumes reports and folds them into one merged document */
    private suspend fun consume() {
        for ((topic, body) in inbox) {
            runCatching {
                // belt and braces: the certificate usually gave us the serial already
                val fromTopic = topic.split('/').getOrNull(1)?.takeIf { it.isNotBlank() && it != "+" }
                if (prefs.serial.isBlank() && fromTopic != null) {
                    prefs.serial = fromTopic
                    client?.let { send(it, Topics.request(fromTopic), Commands.PUSH_ALL) }
                }

                // the printer answers commands on the same topic; those answers are the only
                // honest feedback we get about a print request
                runCatching {
                    val d = JSONObject(body)
                    val pr = d.optJSONObject("print") ?: return@runCatching
                    val cmd = pr.optString("command", "")
                    if (cmd.isNotEmpty() && cmd != "push_status") {
                        val result = pr.optString("result", "")
                        val reason = pr.optString("reason", "")
                        if (result.isNotEmpty()) {
                            log("reply: $cmd -> $result" + (if (reason.isNotEmpty() && reason != result) " ($reason)" else ""))
                            _note.value = "printer replied: $cmd -> $result" +
                                (if (reason.isNotEmpty() && reason != result) " ($reason)" else "")
                        }
                    }
                }

                _reports.value = _reports.value + 1
                merged = Reports.merge(merged, JSONObject(body))
                val p = Reports.parse(merged, prefs.serial.ifBlank { null })
                val now = System.currentTimeMillis()
                _state.value = p.copy(connected = true, asOf = now)
                val h = History.advance(_history.value, _state.value, now)
                if (h != _history.value) {
                    _history.value = h
                    prefs.history = History.toJson(h)
                }
                attempt = 0
                // starting a print clears the note once it is plainly running
                val gs = p.gcodeState?.uppercase()
                if (gs == "RUNNING" || gs == "PREPARE") _note.value = null
                persist()
                if (prefs.serial.isBlank()) p.serial?.let { prefs.serial = it }

            }
        }
    }

    /**
     * Paho hides the useful part in the reason code, and TLS failures hide in the cause.
     * These are three different fixes, so they must be three different messages:
     *   4/5           the access code was rejected by the printer
     *   3             the broker is unavailable
     *   SSLHandshake  TLS, including a pinned-certificate mismatch
     *   SocketTimeout nothing answered at all
     */
    private fun explain(t: Throwable): String {
        val types = generateSequence(t) { it.cause }.map { it.javaClass.simpleName }.toList()
        val chain = generateSequence(t) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
        val rc = generateSequence(t) { it.cause }.filterIsInstance<MqttException>()
            .firstOrNull()?.reasonCode
        return Errors.describeMqtt("${prefs.host}:${prefs.mqttPort}", types, chain, rc)
    }
}
