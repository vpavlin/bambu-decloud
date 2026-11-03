package mesh.office.bambuwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings live in EncryptedSharedPreferences: the access code is a credential and
 * must not land in a plain file, in logs, or in the repo.
 *
 * The certificate fingerprint is also stored here. It is pinned on first successful
 * connect (trust-on-first-use) and a mismatch is treated as a hard error, not a warning
 * you can click through.
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences = run {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var accessCode: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v.trim()).apply()

    var serial: String
        get() = sp.getString("serial", "") ?: ""
        set(v) = sp.edit().putString("serial", v.trim().uppercase()).apply()

    /**
     * Optional address for the SD card (FTPS 990), used instead of [host] when set.
     *
     * An FTP control connection is on one port, but the data connection lands on a random passive
     * port the printer picks. A forwarder (the mesh, a router with a single port opened) can pass
     * 990 and still drop the listing, so the card needs a route of its own — usually the printer's
     * own address while on the same network.
     */
    var cardHost: String
        get() = sp.getString("card_host", "") ?: ""
        set(v) = sp.edit().putString("card_host", v.trim()).apply()

    /**
     * The slicer on the mesh. It slices for this printer and puts the .3mf on the card; this app
     * only asks for it and then decides whether to print.
     */
    var slicerUrl: String
        get() = sp.getString("slicer_url", "http://slicer.proteus.office.mesh") ?: "http://slicer.proteus.office.mesh"
        set(v) = sp.edit().putString("slicer_url", v.trim().trimEnd('/')).apply()

    var mqttPort: Int
        get() = sp.getInt("mqtt_port", 8883)
        set(v) = sp.edit().putInt("mqtt_port", v).apply()

    var camPort: Int
        get() = sp.getInt("cam_port", 6000)
        set(v) = sp.edit().putInt("cam_port", v).apply()

    /**
     * Default ON, and it means what it says: the app may ask the printer to report (pushall),
     * and may not send anything else. Controls are off in this state by construction, not by
     * the UI happening not to show a button.
     */
    var readOnly: Boolean
        get() = sp.getBoolean("read_only", true)
        set(v) = sp.edit().putBoolean("read_only", v).apply()

    /**
     * The last merged report, so that opening the app shows what the printer last said with a
     * timestamp, instead of an empty screen and "connecting…" while Android re-establishes the
     * network. Not a secret, but it lives in the same encrypted store as everything else.
     */
    var lastReport: String?
        get() = sp.getString("last_report", null)
        set(v) = sp.edit().apply { if (v == null) remove("last_report") else putString("last_report", v) }.apply()

    /** job records as JSON; not a secret, but the same encrypted store keeps them together */
    var history: String?
        get() = sp.getString("history", null)
        set(v) = sp.edit().apply { if (v == null) remove("history") else putString("history", v) }.apply()

    /** the last card listing, so the Files screen opens with something rather than a blank wait */
    var cardCache: String?
        get() = sp.getString("card_cache", null)
        set(v) = sp.edit().apply { if (v == null) remove("card_cache") else putString("card_cache", v) }.apply()

    /** when the card cache was written, so we know whether re-reading is worth 18 seconds */
    var cardCacheAt: Long
        get() = sp.getLong("card_cache_at", 0L)
        set(v) = sp.edit().putLong("card_cache_at", v).apply()

    var lastReportAt: Long
        get() = sp.getLong("last_report_at", 0L)
        set(v) = sp.edit().putLong("last_report_at", v).apply()

    fun pin(hostport: String): String? = sp.getString("pin:$hostport", null)

    fun setPin(hostport: String, sha256: String) =
        sp.edit().putString("pin:$hostport", sha256).apply()

    fun clearPin(hostport: String) = sp.edit().remove("pin:$hostport").apply()

    /** what the user saw when a pin mismatch was refused, so the UI can explain it */
    var pendingPinReport: String?
        get() = sp.getString("pin_mismatch", null)
        set(v) = sp.edit().apply { if (v == null) remove("pin_mismatch") else putString("pin_mismatch", v) }.apply()

    val configured: Boolean get() = host.isNotBlank() && accessCode.isNotBlank()
}
