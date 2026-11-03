package mesh.office.bambuwatch

/**
 * Turns a failure into one line a user can act on.
 *
 * Kept as pure functions so the wording can be tested: an earlier version printed
 * "reason 0 (connection accepted)" for a name that could not be resolved, because Paho
 * throws MqttException(0, cause) for client-side failures and 0 is also the CONNACK code
 * for success. A message that contradicts itself is worse than no message.
 */
object Errors {

    /**
     * @return a short meaning for a Paho reason code, or null when the code says nothing useful.
     *         0 is deliberately null: it is "success" for a CONNACK but Paho also uses it when
     *         the failure happened on the client, so printing it is always misleading.
     */
    fun reasonText(rc: Int): String? = when (rc) {
        1 -> "unacceptable protocol version"
        3 -> "broker unavailable"
        4 -> "bad user name or password — access code rejected"
        5 -> "not authorised — access code rejected"
        in 32100..32199 -> "client-side Paho failure"
        else -> null
    }

    private fun has(types: List<String>, name: String) = types.any { it.contains(name, true) }

    private fun firstCause(types: List<String>, chain: String, vararg names: String): Boolean {
        val head = types.firstOrNull() ?: return false
        return names.any { head.contains(it, true) } || names.any { chain.contains(it, true) }
    }

    fun describeMqtt(where: String, types: List<String>, chain: String, reasonCode: Int?): String {
        // concrete causes first: they say more than any code
        if (chain.contains(Tls.CERT_CHANGED)) {
            return "MQTT $where: certificate changed — refusing to connect (pin mismatch)"
        }
        if (has(types, "UnknownHost")) {
            return "MQTT $where: cannot resolve the name — $chain"
        }
        if (has(types, "SSLHandshake") || has(types, "SSLException")) {
            val r = reasonCode?.let { reasonText(it) }
            return "MQTT $where: TLS handshake failed" + (r?.let { " [$it]" } ?: "") + " — $chain"
        }
        val reason = reasonCode?.let { reasonText(it) }
        if (reason != null && (reasonCode == 4 || reasonCode == 5)) {
            return "MQTT $where: access code rejected [reason $reasonCode: $reason]"
        }
        if (has(types, "SocketTimeout")) {
            return "MQTT $where: timed out — nothing answered (no route, or wrong address)"
        }
        if (has(types, "ConnectException")) {
            return if (chain.contains("refused", true)) "MQTT $where: refused — port closed?"
            else "MQTT $where: could not connect — $chain"
        }
        if (reason != null && reasonCode != null) {
            return "MQTT $where: failed [reason $reasonCode: $reason] — $chain"
        }
        // no usable code: say what was actually thrown, and never invent a meaning for 0
        return "MQTT $where: failed — $chain"
    }

    /**
     * The printer closed the connection. What it sent first decides everything: nothing means
     * it rejected us outright, and a few bytes are worth seeing verbatim.
     */
    fun describeEarlyClose(where: String, stage: String, ms: Long, got: Int, bytes: ByteArray): String {
        val hex = if (got == 0) "nothing" else bytes.joinToString(" ") { "%02x".format(it) }
        val n = if (got == 0) "no bytes" else "$got byte${if (got == 1) "" else "s"}"
        return "camera $where: stopped at $stage after ${ms}ms — the printer closed the connection " +
            "after sending $n ($hex)"
    }

    fun describeCamera(where: String, stage: String, ms: Long, types: List<String>, chain: String): String {
        val at = "stopped at $stage after ${ms}ms"
        if (chain.contains(Tls.CERT_CHANGED)) {
            return "camera $where: $at — PINNED CERTIFICATE CHANGED, refusing"
        }
        if (has(types, "UnknownHost")) {
            return "camera $where: cannot resolve the name"
        }
        if (has(types, "SSLHandshake") || has(types, "SSLException")) {
            return "camera $where: $at — TLS handshake failed"
        }
        if (has(types, "SocketTimeout")) {
            return "camera $where: $at — timed out, nothing answered (no route, or wrong address)"
        }
        if (has(types, "ConnectException")) {
            return if (chain.contains("refused", true)) "camera $where: $at — refused, port closed?"
            else "camera $where: $at — could not connect"
        }
        return "camera $where: $at — ${chain.take(110)}"
    }
}
