package mesh.office.bambuwatch

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The printer's certificate is self-signed by "BBL CA", which no public root knows.
 * So we do not "trust everything" and we do not bundle a CA: we pin the leaf.
 *
 *  - first connect for a host: record SHA-256 (trust on first use)
 *  - every later connect: compare; a different certificate is refused
 *  - the refusal carries a marker so the UI can say what changed
 */
object Tls {

    const val CERT_CHANGED = "CERT_CHANGED"

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /**
     * @param key host:port this pin belongs to
     * @param acceptNew write the pin on first use (false when the caller only wants to look)
     */
    fun factory(
        prefs: Prefs,
        key: String,
        acceptNew: Boolean = true,
        onCert: (X509Certificate) -> Unit = {}
    ): SSLSocketFactory =
        factory(
            get = { prefs.pin(it) },
            put = { k, v -> prefs.setPin(k, v) },
            onMismatch = { report -> prefs.pendingPinReport = report },
            key = key,
            acceptNew = acceptNew,
            onCert = onCert
        )

    /** the same policy over any store, so it can be exercised without Android */
    fun factory(
        get: (String) -> String?,
        put: (String, String) -> Unit,
        onMismatch: (String) -> Unit,
        key: String,
        acceptNew: Boolean = true,
        onCert: (X509Certificate) -> Unit = {}
    ): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate presented")
                val sha = fingerprint(leaf)
                // the serial is the certificate CN: use it instead of asking the user
                onCert(leaf)
                val pinned = get(key)
                when {
                    pinned == null -> if (acceptNew) {
                        put(key, sha)
                    } else {
                        throw CertificateException("unpinned host $key")
                    }
                    pinned.equals(sha, ignoreCase = true) -> Unit
                    else -> {
                        onMismatch(
                            "Certificate for $key changed.\npinned: $pinned\npresented: $sha\n" +
                                "subject: ${leaf.subjectX500Principal.name}")
                        throw CertificateException(CERT_CHANGED)
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return ctx.socketFactory
    }

    /** subject CN of the printer certificate is the printer serial */
    fun cnOf(cert: X509Certificate): String? =
        Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)
}
