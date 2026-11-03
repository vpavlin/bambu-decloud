package mesh.office.bambuwatch

import java.net.URLEncoder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The slicer on the mesh. It slices for the A1 and puts the result on the printer's card,
 * but it never starts a print — that stays here, with [Commands] and its read-only gate.
 *
 * Endpoints, all plain JSON except the toolpath:
 *   GET  /presets                  what it can slice with
 *   GET  /jobs                     recent jobs
 *   GET  /jobs/{id}                one job
 *   GET  /jobs/{id}/toolpath.bin   the 3D data (see [Toolpath])
 *   POST /jobs                     multipart upload + ?process=&filament=&copies=&rx=&ry=&scale=
 *   POST /jobs/{id}/reslice        same upload, new settings
 *   POST /jobs/{id}/upload         ONLY NOW does it reach the printer's card
 *   DELETE /jobs/{id}
 */
object Slicer {

    data class Presets(val process: List<String>, val filament: List<String>,
                       val defaultProcess: String, val defaultFilament: String)

    data class Job(
        val id: String,
        val name: String,
        val state: String,
        val seconds: Int?,
        val grams: Double?,
        val layers: Int?,
        val cardPath: String?,
        val error: String?,
        val params: Map<String, String>,
        val hasPreview: Boolean,
    ) {
        val openable: Boolean get() = state == "sliced" || state == "ready"
        val ready: Boolean get() = state == "ready"
        val busy: Boolean get() = state == "queued" || state == "slicing" || state == "uploading"
    }

    private const val CONNECT_MS = 6000
    private const val READ_MS = 30000

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        c.instanceFollowRedirects = true
        return c
    }

    private fun bodyBytes(c: HttpURLConnection): ByteArray {
        val raw = BufferedInputStream(c.inputStream)
        val stream = if ((c.contentEncoding ?: "").contains("gzip", true)) GZIPInputStream(raw) else raw
        return stream.use { input -> ByteArrayOutputStream().also { input.copyTo(it, 64 * 1024) }.toByteArray() }
    }

    private fun body(c: HttpURLConnection): String {
        val raw = c.inputStream
        val stream = if ((c.contentEncoding ?: "").contains("gzip", true))
            GZIPInputStream(raw) else raw
        return stream.bufferedReader().use { it.readText() }
    }

    private fun fail(c: HttpURLConnection, what: String): Nothing {
        val code = c.responseCode
        val text = runCatching {
            c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        val msg = runCatching { JSONObject(text).optString("error") }.getOrDefault("").ifBlank { text }
        c.disconnect()
        throw IOException("$what: HTTP $code${if (msg.isBlank()) "" else " — " + msg.take(160)}")
    }

    private fun getJson(base: String, path: String): JSONObject {
        val c = open(base.trimEnd('/') + path, "GET")
        try {
            if (c.responseCode !in 200..299) fail(c, "GET $path")
            return JSONObject(body(c))
        } finally {
            c.disconnect()
        }
    }

    fun presets(base: String): Presets {
        val j = getJson(base, "/presets")
        val av = j.getJSONObject("available")
        val def = j.getJSONObject("defaults")
        fun list(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
        return Presets(
            process = list(av.getJSONArray("process")),
            filament = list(av.getJSONArray("filament")),
            defaultProcess = def.optString("process"),
            defaultFilament = def.optString("filament"),
        )
    }

    fun jobs(base: String): List<Job> {
        val j = getJson(base, "/jobs")
        val arr = j.getJSONArray("jobs")
        return (0 until arr.length()).map { parseJob(arr.getJSONObject(it)) }
    }

    fun job(base: String, id: String): Job = parseJob(getJson(base, "/jobs/$id"))

    private fun parseJob(o: JSONObject): Job {
        val est = o.optJSONObject("estimate") ?: JSONObject()
        val params = o.optJSONObject("params") ?: JSONObject()
        val map = params.keys().asSequence().associateWith { params.optString(it) }
        return Job(
            id = o.getString("id"),
            name = o.optString("name"),
            state = o.optString("state"),
            seconds = if (est.has("seconds")) est.optInt("seconds") else null,
            grams = if (est.has("grams")) est.optDouble("grams") else null,
            layers = if (est.has("layers")) est.optInt("layers") else null,
            cardPath = o.optString("card_path").ifBlank { null },
            error = o.optString("error").ifBlank { null },
            params = map,
            hasPreview = o.optBoolean("preview"),
        )
    }

    /** The 3D stream. The server gzips it and HttpURLConnection will not unpack it for us. */
    fun toolpath(base: String, id: String): Toolpath {
        val c = open(base.trimEnd('/') + "/jobs/$id/toolpath.bin", "GET")
        try {
            if (c.responseCode !in 200..299) fail(c, "toolpath")
            return Toolpath.parse(bodyBytes(c))
        } finally {
            c.disconnect()
        }
    }

    fun upload(base: String, file: File, params: Map<String, String>): String {
        val boundary = "----bw" + System.nanoTime()
        val query = params.entries.filter { it.value.isNotBlank() }
            .joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = base.trimEnd('/') + "/jobs" + if (query.isBlank()) "" else "?$query"
        val c = open(url, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            DataOutputStream(c.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            if (c.responseCode !in 200..299) fail(c, "upload")
            return JSONObject(body(c)).getString("job")
        } finally {
            c.disconnect()
        }
    }

    fun reslice(base: String, id: String, params: Map<String, String>) {
        val query = params.entries.filter { it.value.isNotBlank() }
            .joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val c = open(base.trimEnd('/') + "/jobs/$id/reslice?$query", "POST")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(ByteArray(0)) }
            if (c.responseCode !in 200..299) fail(c, "reslice")
        } finally {
            c.disconnect()
        }
    }

    /** The only call that touches the printer. After this the .3mf is on the card. */
    fun sendToPrinter(base: String, id: String, tray: String = ""): Job {
        // The slot chosen here must travel with the print: the AMS mapping lives in the print
        // command, not in the gcode, so a slot picked after slicing is only honoured if we
        // send it now. Without it the job keeps use_ams=false and the printer loads nothing.
        val q = if (tray.isNotBlank()) "?tray=" + URLEncoder.encode(tray, "UTF-8") else ""
        val c = open(base.trimEnd('/') + "/jobs/$id/upload" + q, "POST")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(ByteArray(0)) }
            if (c.responseCode !in 200..299) fail(c, "send")
        } finally {
            c.disconnect()
        }
        var last = job(base, id)
        repeat(120) {
            if (!last.busy) return last
            Thread.sleep(700)
            last = job(base, id)
        }
        return last
    }

    fun delete(base: String, id: String) {
        val c = open(base.trimEnd('/') + "/jobs/$id", "DELETE")
        try {
            if (c.responseCode !in 200..299) fail(c, "delete")
        } finally {
            c.disconnect()
        }
    }

    /** A job that is on the card, as the printer's own file list would describe it. */
    fun cardFile(job: Job): CardFile? {
        val path = job.cardPath ?: return null
        val name = path.substringAfterLast('/')
        if (!CardFile.printable(name)) return null
        return CardFile(name = name, path = path, size = 0, modified = 0)
    }
}
