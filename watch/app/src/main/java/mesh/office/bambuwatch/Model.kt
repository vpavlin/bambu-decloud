package mesh.office.bambuwatch

import org.json.JSONArray
import org.json.JSONObject

data class Tray(
    val slot: String,          // "0".."3" or "255" etc.
    val material: String?,     // PLA, PETG, ...
    val colorHex: String?,     // RRGGBB or RRGGBBAA as the printer reports it
    val remain: Int?,          // -1 means "unknown", as the printer uses
    val active: Boolean
) {
    /** an empty AMS slot reports no tray_type; that is not "unknown", it is "nothing loaded" */
    val isLoaded: Boolean get() = !material.isNullOrBlank()
}

/** 838969536 -> "192.168.1.50": the printer sends the address as a little-endian 32-bit int */
internal fun ipv4FromLittleEndian(v: Long): String? {
    if (v <= 0 || v > 0xFFFFFFFFL) return null
    val b = (0..3).map { ((v shr (8 * it)) and 0xFF).toInt() }
    if (b.any { it == 0 || it == 255 }) return null
    return b.joinToString(".")
}

data class PrinterState(
    val connected: Boolean = false,
    val gcodeState: String? = null,
    val statusWord: String = "offline",
    val printError: Long? = null,
    val hms: List<String> = emptyList(),
    val fileName: String? = null,
    val progress: Int? = null,
    val layer: Int? = null,
    val totalLayers: Int? = null,
    val remainingMin: Int? = null,
    val nozzle: Double? = null,
    val nozzleTarget: Double? = null,
    val bed: Double? = null,
    val bedTarget: Double? = null,
    val chamber: Double? = null,
    val trays: List<Tray> = emptyList(),
    val activeTray: String? = null,
    val serial: String? = null,
    val machine: String? = null,
    /** the printer's own IPv4 address, as it reports it in net.info[0].ip (little-endian) */
    val ip: String? = null,

    /** the path on the SD card, when the printer reports one; empty while idle some firmwares */
    val gcodeFile: String? = null,
    /** when this state was received; null for a state we have never had from the printer */
    val asOf: Long? = null,
) {
    /**
     * Only show a chamber tile when the number could be a real chamber temperature.
     * The A1 has no chamber sensor and still reports chamber_temper = 5, so "!= 0" put a
     * bogus 5 °C tile on the screen; a genuine chamber reads near ambient or above.
     */
    val hasChamber: Boolean get() = chamber != null && chamber >= 10.0 && chamber <= 100.0
}

/**
 * The printer sends ONE full report (in reply to pushall) and then partial updates.
 * Keeping the merged JSON and re-deriving the state from it is the only way partials
 * make sense — a partial report usually carries a single field.
 */
object Reports {

    fun merge(into: JSONObject, update: JSONObject): JSONObject {
        val out = JSONObject(into.toString())
        for (k in update.keys()) {
            val nv = update.opt(k)
            val ov = out.opt(k)
            if (nv is JSONObject && ov is JSONObject) out.put(k, merge(ov, nv)) else out.put(k, nv)
        }
        return out
    }

    fun parse(root: JSONObject, serialFallback: String?): PrinterState {
        val p = root.optJSONObject("print") ?: JSONObject()
        val info = root.optJSONObject("info") ?: JSONObject()

        val gcode = p.optString("gcode_state", "").ifBlank { null }

        val hmsList = mutableListOf<String>()
        val hms = p.optJSONArray("hms")
        if (hms != null) for (i in 0 until hms.length()) {
            val o = hms.optJSONObject(i) ?: continue
            // The printer sends attr and code as numbers; the error table keys on the pair as
            // sixteen hex digits (HMS_0300_0100_0001_0001, separators removed). Build that key
            // here — showing the user two naked integers explains nothing (2026-11-03).
            val attr = o.optLong("attr", 0L)
            val code = o.optLong("code", 0L)
            hmsList += if (attr != 0L && code != 0L) "%08X%08X".format(attr, code)
                       else o.optString("code", "").ifBlank { "0x%08X".format(code) }
        }

        val ams = p.optJSONObject("ams")
        val trays = mutableListOf<Tray>()
        var active: String? = ams?.optString("tray_now", "")?.ifBlank { null }
        val units = ams?.optJSONArray("ams")
        if (units != null) for (u in 0 until units.length()) {
            val unit = units.optJSONObject(u) ?: continue
            val arr = unit.optJSONArray("tray") ?: continue
            for (t in 0 until arr.length()) {
                val tray = arr.optJSONObject(t) ?: continue
                val slot = tray.optString("id", "$t")
                trays += Tray(
                    slot = slot,
                    material = tray.optString("tray_type", "").ifBlank { null },
                    colorHex = normaliseColour(tray.optString("tray_color", "")),
                    remain = tray.optInt("remain", -1).let { if (it < 0) null else it },
                    active = slot == active
                )
            }
        }
        if (active == "255") active = null

        return PrinterState(
            connected = true,
            gcodeState = gcode,
            // a connected printer that has not reported gcode_state yet is not offline: the
            // first message after pushall is often a partial, and calling that "offline" made
            // a working connection look broken
            statusWord = if (gcode == null) "unknown" else word(gcode),
            printError = p.optLong("print_error", 0L).takeIf { it != 0L },
            hms = hmsList.distinct(),
            fileName = name(p),
            progress = p.optInt("mc_percent", -1).takeIf { it >= 0 },
            layer = p.optInt("layer_num", -1).takeIf { it >= 0 },
            totalLayers = p.optInt("total_layer_num", -1).takeIf { it >= 0 },
            remainingMin = p.optInt("mc_remaining_time", -1).takeIf { it >= 0 },
            gcodeFile = p.optString("gcode_file", "").ifBlank { null },
            ip = p.optJSONObject("net")?.optJSONArray("info")?.optJSONObject(0)
                ?.optLong("ip", -1)?.let { if (it > 0) ipv4FromLittleEndian(it) else null },
            nozzle = p.optNullableDouble("nozzle_temper"),
            bed = p.optNullableDouble("bed_temper"),
            chamber = p.optNullableDouble("chamber_temper"),
            trays = trays,
            activeTray = active,
            serial = info.optString("serial_number", "").ifBlank { serialFallback },
            machine = info.optString("machine_name", "").ifBlank { null }
        )
    }

    private fun name(p: JSONObject): String? =
        p.optString("subtask_name", "").ifBlank { null }
            ?: p.optString("gcode_file", "").ifBlank { null }

    private fun JSONObject.optNullableDouble(k: String): Double? =
        if (!has(k)) null else optDouble(k).takeIf { !it.isNaN() }

    /** the printer reports colours as RRGGBB or RRGGBBAA, sometimes with a leading # */
    private fun normaliseColour(c: String): String? {
        val s = c.trim().removePrefix("#")
        if (s.length < 6) return null
        val hex = s.take(8)
        if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "#" + hex.take(6)
    }

    fun word(gcode: String?): String = when (gcode?.uppercase()) {
        null, "" -> "offline"
        "IDLE" -> "idle"
        "RUNNING" -> "printing"
        "PAUSE" -> "paused"
        "FINISH" -> "finished"
        "FAILED" -> "error"
        "PREPARE" -> "preparing"
        "SLICING" -> "slicing"
        else -> gcode.lowercase()
    }
}

