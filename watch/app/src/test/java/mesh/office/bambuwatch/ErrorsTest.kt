package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording is the only thing a user sees when it fails, so it is tested — including the
 * regression that prompted this: Paho throws MqttException(0, cause) for client-side
 * failures, and 0 is also the CONNACK code for success. Printing "reason 0 (connection
 * accepted)" next to "cannot resolve the name" is a contradiction.
 */
class ErrorsTest {

    private val unknownHostTypes = listOf("MqttException", "UnknownHostException")
    private val unknownHostChain =
        "MqttException: MqttException <- UnknownHostException: printer.lan.proteus.office.mesh"

    @Test
    fun reasonZeroIsNeverReportedAsSuccess() {
        assertNull("0 means 'client-side failure' to Paho, not a CONNACK success", Errors.reasonText(0))
        val msg = Errors.describeMqtt("h:8883", unknownHostTypes, unknownHostChain, 0)
        assertFalse("must not claim the connection was accepted", msg.contains("connection accepted"))
        assertFalse("must not print a meaningless code", msg.contains("reason 0"))
    }

    @Test
    fun unknownHostSaysSo() {
        val msg = Errors.describeMqtt("h:8883", unknownHostTypes, unknownHostChain, 0)
        assertTrue(msg, msg.contains("cannot resolve"))
        assertTrue("the name should appear for context", msg.contains("h:8883"))
    }

    @Test
    fun badCredentialsAreNamedWithTheirCodes() {
        for (rc in listOf(4, 5)) {
            val msg = Errors.describeMqtt("h:8883", listOf("MqttException"), "MqttException: x", rc)
            assertTrue("code $rc should say the access code was rejected", msg.contains("access code rejected"))
            assertTrue("code $rc should still show the number", msg.contains("reason $rc"))
        }
    }

    @Test
    fun brokerUnavailableIsDistinguishedFromBadCredentials() {
        val msg = Errors.describeMqtt("h:8883", listOf("MqttException"), "MqttException: x", 3)
        assertTrue(msg.contains("broker unavailable"))
        assertFalse(msg.contains("access code rejected"))
    }

    @Test
    fun tlsFailureIsNotReportedAsATimeout() {
        val msg = Errors.describeMqtt("h:8883", listOf("MqttException", "SSLHandshakeException"),
            "MqttException <- SSLHandshakeException: handshake failed", 0)
        assertTrue(msg, msg.contains("TLS handshake failed"))
        assertFalse("a TLS failure must not look like a timeout", msg.contains("timed out"))
    }

    @Test
    fun pinsMismatchOutranksEverything() {
        val msg = Errors.describeMqtt("h:8883", listOf("CertificateException"),
            Tls.CERT_CHANGED, 4)
        assertTrue(msg, msg.contains("certificate changed"))
        assertFalse("do not also blame the access code", msg.contains("access code"))
    }

    @Test
    fun timeoutAndRefusalReadDifferently() {
        val t = Errors.describeMqtt("h:8883", listOf("MqttException", "SocketTimeoutException"),
            "MqttException <- SocketTimeoutException: after 8000ms", null)
        val r = Errors.describeMqtt("h:8883", listOf("MqttException", "ConnectException"),
            "MqttException <- ConnectException: Connection refused", null)
        assertTrue(t.contains("timed out"))
        assertTrue(r.contains("refused"))
        assertFalse("these must not be the same sentence", t == r)
    }

    @Test
    fun cameraUnknownHostNamesNoStage() {
        val msg = Errors.describeCamera("h:6000", "tcp connect", 0L,
            listOf("Stage", "UnknownHostException"), "UnknownHostException: h")
        assertTrue(msg, msg.contains("cannot resolve the name"))
        assertFalse("ms is meaningless when nothing was dialled", msg.contains("after 0ms"))
    }

    @Test
    fun cameraReportsTheStageAndElapsed() {
        val msg = Errors.describeCamera("h:6000", "tls handshake", 512L,
            listOf("Stage", "SSLHandshakeException"), "SSLHandshakeException: bad")
        assertTrue(msg.contains("stopped at tls handshake after 512ms"))
        assertTrue(msg.contains("TLS handshake failed"))
    }

    @Test
    fun earlyCloseSaysWhetherThePrinterSaidAnything() {
        val silent = Errors.describeEarlyClose("h:6000", "auth sent (layout standard), waiting for the first frame", 1972L, 0, ByteArray(0))
        assertTrue(silent, silent.contains("no bytes"))
        assertTrue(silent.contains("stopped at auth sent"))
        assertTrue(silent.contains("1972ms"))

        val spoke = Errors.describeEarlyClose("h:6000", "auth sent, waiting for the first frame", 400L, 4,
            byteArrayOf(0x0f, 0x00, 0xff.toByte(), 0x7f))
        assertTrue(spoke, spoke.contains("4 bytes"))
        assertTrue("the bytes must be visible for diagnosis", spoke.contains("0f 00 ff 7f"))
    }

    @Test
    fun cameraTimeoutPointsAtRouting() {
        val msg = Errors.describeCamera("h:6000", "tcp connect", 8000L,
            listOf("Stage", "SocketTimeoutException"), "SocketTimeoutException: x")
        assertTrue(msg.contains("timed out"))
        assertTrue("should hint at the likely cause", msg.contains("no route, or wrong address"))
    }
}
