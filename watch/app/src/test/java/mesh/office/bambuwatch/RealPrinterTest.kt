package mesh.office.bambuwatch

import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the app's real MQTT path — Paho, Tls.factory, Topics, Reports — against the actual
 * printer on this LAN, so "works on the mock" cannot hide a bug.
 *
 * Skipped unless BAMBU_CODE_FILE points at a file holding the access code (mode 600). The code
 * is read from that file and never printed.
 *
 *     BAMBU_CODE_FILE=~/.bambu-code ./build.sh testDebugUnitTest --tests '*RealPrinterTest*'
 */
class RealPrinterTest {

    private val codeFile = System.getenv("BAMBU_CODE_FILE")
    private val host = System.getenv("BAMBU_HOST") ?: "192.168.1.50"
    private val port = (System.getenv("BAMBU_PORT") ?: "8883").toInt()

    private fun code(): String = File(codeFile!!).readText().trim()

    @Test
    fun theAppsOwnMqttPathReachesThePrinter() {
        assumeTrue("set BAMBU_CODE_FILE to run against the real printer", !codeFile.isNullOrBlank())
        val code = code()
        assertTrue("code file looks empty", code.isNotBlank())

        var pin: String? = null
        var serialFromCert: String? = null

        // exactly what Printer.connectBlocking builds
        val factory = Tls.factory(
            get = { pin },
            put = { _, v -> pin = v },
            onMismatch = { },
            key = "$host:$port",
            onCert = { cert -> Tls.cnOf(cert)?.let { serialFromCert = it } }
        )

        val client = MqttClient("ssl://$host:$port", "jvmprobe${System.nanoTime() % 100000}",
            MemoryPersistence())
        val reports = LinkedBlockingQueue<String>()
        val subscribedTo = java.util.concurrent.atomic.AtomicReference<String>()
        val connack = java.util.concurrent.atomic.AtomicBoolean(false)
        val connectSignal = LinkedBlockingQueue<Unit>()

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connack.set(true)
                connectSignal.add(Unit)   // as in Printer: no blocking calls on this thread
            }
            override fun connectionLost(cause: Throwable?) {
                println("  connectionLost: ${cause?.javaClass?.simpleName}: ${cause?.message}")
            }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.let { reports.add(it.payload.toString(Charsets.UTF_8)) }
            }
            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
        })

        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            userName = "bblp"
            password = code.toCharArray()
            isHttpsHostnameVerificationEnabled = false   // as in Printer: pinning replaces it
            socketFactory = factory
        }
        client.connect(opts)

        // doSubscribe(), off the callback thread
        connectSignal.poll(10, TimeUnit.SECONDS)
        val topic = Topics.reportFor(serialFromCert)
        subscribedTo.set(topic)
        client.subscribe(topic, 1)
        Topics.requestFor(serialFromCert)?.let {
            client.publish(it, MqttMessage(Topics.PUSH_ALL.toByteArray()).apply { qos = 0 })
        }
        println("  subscribed to $topic and published pushall")

        val deadline = System.currentTimeMillis() + 20_000
        var merged = JSONObject()
        var count = 0
        while (System.currentTimeMillis() < deadline) {
            val one = reports.poll(1, TimeUnit.SECONDS) ?: continue
            merged = Reports.merge(merged, JSONObject(one))
            count++
            // stop once the printer has told us its actual state, not just a partial
            if (merged.optJSONObject("print")?.optString("gcode_state")?.isNotBlank() == true) break
        }
        val first: String? = if (count > 0) merged.toString() else null
        runCatching { client.disconnectForcibly(500, 500, false); client.close() }

        println("  CONNACK ok      : ${connack.get()}")
        println("  serial from cert: ${serialFromCert ?: "(none!)"}")
        println("  subscribed to   : ${subscribedTo.get()}")
        println("  reports merged  : $count (${first?.length ?: 0} bytes of merged state)")
        println("  payload keys    : ${JSONObject(first ?: "{}").keys().asSequence().toList()}")
        println("  print keys      : ${JSONObject(first ?: "{}").optJSONObject("print")?.keys()?.asSequence()?.toList()?.take(8)}")

        assertTrue("TLS handshake did not surface the certificate CN", serialFromCert != null)
        assertTrue("subscribed to the wildcard, which the printer drops", !subscribedTo.get()!!.contains("+"))
        assertTrue("no report arrived from the printer", first != null)

        val st = Reports.parse(JSONObject(Reports.merge(JSONObject(), JSONObject(first!!))), serialFromCert)
        println("  parsed state    : ${st.statusWord}, nozzle=${st.nozzle}, trays=${st.trays.size}")
        assertTrue("state did not parse", st.connected)
    }
}
