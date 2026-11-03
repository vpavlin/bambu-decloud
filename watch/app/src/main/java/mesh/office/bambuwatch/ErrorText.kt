package mesh.office.bambuwatch

import android.content.Context

/**
 * Bambu's own error text, offline.
 *
 * Two code spaces, both numeric and both meaningless to a person at 2 a.m.:
 *   - `print_error` — an eight-digit hex code reported in the status, e.g. 83935248 = 0500C010
 *     = "MicroSD Card read/write exception: please reinsert or replace the MicroSD Card."
 *   - HMS — reported as `HMS_0300_0100_0001_0001`, sixteen hex digits once the separators go.
 *
 * The table is bundled (5,944 codes, 64 kB gzipped) and was taken from the ha-bambulab project's
 * `hms_error_text/hms_en.json`, which is itself generated from Bambu's own strings. Provenance is
 * recorded here because a wrong meaning is worse than none: an unknown code still shows as a code.
 */
object ErrorText {

    private var table: Map<String, String>? = null

    fun load(ctx: Context) {
        if (table != null) return
        table = runCatching {
            // NOTE: the asset is stored as errors.tsv.gz in the source tree, but aapt DECOMPRESSES
            // it when packaging — the APK contains a plain assets/errors.tsv. Reading the .gz name
            // here would silently yield an empty table (checked in the built APK, not assumed).
            ctx.assets.open("errors.tsv").bufferedReader().useLines { parse(it) }
        }.getOrElse { emptyMap() }
    }

    fun size(): Int = table?.size ?: 0

    /** the parsing, split out so it can be tested without an Android context */
    fun parse(lines: Sequence<String>): Map<String, String> = lines.mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
    }.toMap()

    /** a print_error reported as a decimal number */
    fun forPrintError(code: Long): String? = table?.get("%08X".format(code))

    /** an HMS code as the report gives it: HMS_0300_0100_0001_0001 */
    fun forHms(code: String): String? {
        val key = code.uppercase().removePrefix("HMS").replace("_", "").trim()
        return table?.get(key)
    }

    /** the same lookup against a supplied table, so tests do not need an Android context */
    fun explainOrNull(t: Map<String, String>, key: String): String? = when {
        key.startsWith("print_error:") ->
            key.removePrefix("print_error:").toLongOrNull()?.let { t["%08X".format(it)] }
        key.startsWith("hms:") ->
            t[key.removePrefix("hms:").uppercase().removePrefix("HMS").replace("_", "").trim()]
        else -> null
    }

    /** what Codes.explain used to have nowhere to look */
    fun explain(key: String): String? = when {
        key.startsWith("print_error:") -> key.removePrefix("print_error:").toLongOrNull()?.let { forPrintError(it) }
        key.startsWith("hms:") -> forHms(key.removePrefix("hms:"))
        else -> null
    }
}
