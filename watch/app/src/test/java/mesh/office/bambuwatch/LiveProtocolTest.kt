package mesh.office.bambuwatch

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Drives the REAL client path against the mock printer in ../mock: TLS with a pinned
 * self-signed leaf, MQTT as user bblp, subscribe to device/+/report, publish pushall,
 * then merge what arrives. Skipped when the mock is not running, so CI stays green.
 */
class LiveProtocolTest {

    private fun mockUp(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 700) }
    }.isSuccess

    @Test
    fun pinnedTlsRejectsAWrongPin() {
        assumeTrue("mock printer not running on 8883", mockUp(8883))
        var reported: String? = null
        val factory = Tls.factory(
            get = { "AA:BB:CC" },                 // a pin that cannot match
            put = { _, _ -> },
            onMismatch = { reported = it },
            key = "127.0.0.1:8883",
            acceptNew = false
        )
        val c = MqttClient("ssl://127.0.0.1:8883", "pin-test", MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = "bblp"; password = "00000000".toCharArray()
            socketFactory = factory; connectionTimeout = 5
        }
        val failed = runCatching { c.connect(opts) }.isFailure
        assertTrue("a mismatched pin must not connect", failed)
        assertNotNull("the mismatch must be reported to the user", reported)
    }

    @Test
    fun connectRequestFullStateAndMergePartials() {
        assumeTrue("mock printer not running on 8883", mockUp(8883))

        var pin: String? = null
        val factory = Tls.factory(
            get = { pin },
            put = { _, v -> pin = v },
            onMismatch = { },
            key = "127.0.0.1:8883"
        )

        val c = MqttClient("ssl://127.0.0.1:8883", "live-test-${System.nanoTime()}", MemoryPersistence())
        var merged = JSONObject()
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        c.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String?, message: org.eclipse.paho.client.mqttv3.MqttMessage?) {
                got.add(message!!.payload.toString(Charsets.UTF_8))
            }
            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
        })

        c.connect(MqttConnectOptions().apply {
            userName = "bblp"; password = "00000000".toCharArray(); socketFactory = factory
            isCleanSession = true; connectionTimeout = 8
        })
        assertNotNull("certificate must have been pinned on first use", pin)

        c.subscribe("device/+/report", 1)
        c.publish("device/YOUR_SERIAL/request",
            org.eclipse.paho.client.mqttv3.MqttMessage(
                """{"pushing":{"sequence_id":"0","command":"pushall"}}""".toByteArray()))

        // Collect the full report and the partials that follow it. The mock drifts
        // mc_percent a few times and then flips gcode_state to PAUSE, so the states we
        // observe prove that later partials are APPLIED to earlier state, not replacing it.
        val seenStates = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val one = got.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
            merged = Reports.merge(merged, JSONObject(one))
            Reports.parse(merged, null).statusWord.let { if (seenStates.lastOrNull() != it) seenStates += it }
            if (merged.optJSONObject("print")?.optString("gcode_state") == "PAUSE") break
        }
        // one more drain in case a message landed between poll and break
        while (true) {
            val one = got.poll() ?: break
            merged = Reports.merge(merged, JSONObject(one))
        }

        c.disconnectForcibly(500, 500, false); c.close()

        val st = Reports.parse(merged, null)
        assertTrue("expected at least the full report, got ${merged.length()} keys", merged.length() > 0)
        assertTrue("the full report must read as printing, saw $seenStates",
            seenStates.contains("printing"))
        assertEquals("the later partial must be applied, not ignored", "paused", st.statusWord)
        assertEquals("YOUR_SERIAL", st.serial)
        assertEquals("benchy_PLA_0.2_18m.3mf", st.fileName)
        assertEquals(4, st.trays.size)
        assertEquals("PETG", st.trays[1].material)
        assertTrue("a partial update must have advanced progress", (st.progress ?: 0) >= 47)
        assertTrue("nozzle temperature must survive partials", st.nozzle!! > 200.0)
    }
}
