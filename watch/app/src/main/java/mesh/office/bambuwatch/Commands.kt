package mesh.office.bambuwatch

import org.json.JSONObject

/**
 * Everything this app can send to the printer, and the rule that decides what a read-only
 * app may send.
 *
 * Read-only is not a label here: [isReadOnlySafe] is the single predicate that stands between
 * the app and a command that would change what the printer is doing, and [Printer] routes every
 * publish through it. If a future control is added and not classified here, it is refused by
 * default — the test that covers this asserts exactly that.
 */
object Commands {

    /** ask the printer to report everything it knows; changes nothing about a print */
    const val PUSH_ALL = """{"pushing":{"sequence_id":"0","command":"pushall"}}"""

    fun pause(seq: String): String = printCmd(seq, "pause")
    fun resume(seq: String): String = printCmd(seq, "resume")
    fun stop(seq: String): String = printCmd(seq, "stop")

    /**
     * Reprint a job the printer itself told us about. It needs the file path from the SD card;
     * without one this is not offered at all rather than guessed at.
     *
     * Shape follows the LAN-mode project_file command. UNVERIFIED ON HARDWARE as of 2026-10-23:
     * no report captured during a live print has yet carried a gcode_file, and starting a print
     * needs the owner's consent, so this stays behind the same read-only switch and a confirmation.
     */
    fun reprint(seq: String, file: String, name: String, useAms: Boolean = true): String =
        reprint(seq, file, file, name, useAms)

    /**
     * Start a file that is already on the card.
     *
     * A bare `.gcode` is a toolpath: the printer starts those with `gcode_file` and its `param`.
     * A `.3mf` is a project that contains one, so it goes through `project_file` and the inner
     * `Metadata/plate_1.gcode`. Václav's report (23-10) is that `project_file` alone did nothing —
     * the printer took the command and kept reporting the old job — which is why the two are
     * separated here instead of one payload guessed for both.
     */
    /** a short, secret-free description of what we asked the printer to do */
    fun describe(payload: String): String = runCatching {
        val p = org.json.JSONObject(payload).getJSONObject("print")
        val cmd = p.optString("command", "?")
        val param = p.optString("param", "")
        if (param.isNotBlank()) "$cmd of $param" else cmd
    }.getOrElse { "unreadable payload: " + payload.take(60) }

    /**
     * Every plausible way to start this file, most likely first.
     *
     * Measured on a RUNNING print (2026-10-23): the printer describes the job it is executing as
     * `gcode_file = "RasPi5Case_withTopMountNVME.gcode.3mf"` — a BARE NAME, no /cache/ prefix, and
     * `print_type = "local"`. So the absolute path I was sending (0.27) contradicts the printer's
     * own vocabulary. The candidates below are ordered by that evidence, not by documentation.
     */
    /**
     * Calibration policy, from Václav (2026-11-02): vibration/resonance is NEVER wanted,
     * bed levelling and flow calibration are fine. His printer, his ears.
     */
    fun printCandidates(seq: String, f: CardFile, includeRaw: Boolean = true,
                        amsSlot: Int? = null): List<String> {
        val name = f.name.replace("\"", "'")
        // the URL must be the path the printer can actually resolve. The print pipeline reads
        // the ROOT of the card, while uploads from outside land in /cache — which is why a job
        // named ftp:///cache/x answered with the card read exception every time.
        return if (name.lowercase().endsWith(".gcode")) listOfNotNull(
            gcodeFileLiteral(seq, name),               // as the printer names its own jobs
            if (includeRaw) gcodeFile("$seq.1", absolute(name)) else null,  // absolute, as documented
            projectFile("$seq.2", name, name)
        ) else {
            val bare = name.substringBeforeLast(".gcode")
            listOf(
                projectFile(seq, f.path, name, amsSlot = amsSlot),  // url ftp:///<path as stored>
                projectFileUrl("$seq.1", name, "ftp:///$name", amsSlot),  // url ftp:///<name>
                projectFileFileField("$seq.2", name, bare)     // file=<name>, url empty
            )
        }
    }

    /** project_file with an explicit url, for trying the forms the printer might expect */
    fun projectFileUrl(seq: String, name: String, url: String, amsSlot: Int? = null): String =
        projectFile(seq, name, name).replace("\"url\":\"ftp:///cache/$name\"", "\"url\":\"$url\"")

    /** the variant that names the file in `file` and leaves url empty */
    fun projectFileFileField(seq: String, name: String, subtask: String): String =
        """{"print":{"sequence_id":"$seq","command":"project_file","param":"Metadata/plate_1.gcode","project_id":"0","profile_id":"0","task_id":"0","subtask_id":"0","subtask_name":"$subtask","file":"$name","url":"","md5":"","timelapse":false,"bed_type":"auto","bed_levelling":true,"flow_cali":true,"vibration_cali":false,"layer_inspect":false,"ams_mapping":"","use_ams":true}}"""

    /** true when the printer can plausibly start this file at all */
    fun looksPrintable(name: String): Boolean {
        val n = name.lowercase()
        // a project (.3mf, incl. *.gcode.3mf) carries what the printer needs; a raw .gcode often does not
        return n.endsWith(".3mf")
    }

    fun printCard(seq: String, f: CardFile, amsSlot: Int? = null): String =
        if (f.path.lowercase().endsWith(".gcode")) gcodeFile(seq, f.path)
        else projectFile(seq, f.path, f.name, amsSlot = amsSlot)

    /**
     * A bare `.gcode` on the card: `gcode_file` with the file's path.
     * Reference: OpenBambuAPI mqtt.md, "print.gcode_file".
     */
    /** the path exactly as given — the printer names its own running job this way */
    fun gcodeFileLiteral(seq: String, path: String): String =
        """{"print":{"sequence_id":"$seq","command":"gcode_file","param":"${path.replace("\"", "")}"}}"""

    fun gcodeFile(seq: String, path: String): String =
        """{"print":{"sequence_id":"$seq","command":"gcode_file","param":"${absolute(path)}"}}"""

    /**
     * The path as the printer's own filesystem sees it.
     *
     * Measured 23-10: `gcode_file` with a bare name (`80365_plate_1.gcode`) is answered
     * `result: fail` — the printer looks for an absolute path, and the file is in /cache.
     */
    fun absolute(path: String): String {
        val p = path.replace("\"", "")
        return when {
            p.startsWith("/") -> p
            p.contains('/') -> "/$p"
            else -> "/cache/$p"
        }
    }

    /**
     * A `.3mf` is a project: `project_file` with the inner gcode as `param` and the file as an
     * ftp `url`. Field names matter here and are easy to get subtly wrong — the reference spells
     * it `bed_levelling`, keeps the four ids, and puts the whole path in `url` while `file` stays
     * empty. Václav's 0.20-0.24 attempts all sent a different shape and the printer ignored them
     * without complaint.
     * Reference: OpenBambuAPI mqtt.md, "print.project_file".
     */
    fun projectFile(seq: String, path: String, name: String, useAms: Boolean = true,
                    amsSlot: Int? = null): String {
        val safeName = name.replace("\"", "'")
        val bare = safeName.substringBeforeLast(".gcode").substringBeforeLast('.')
        val urlPath = if (path.startsWith("/")) path else "/cache/$path"
        val url = "ftp://$urlPath".replace("\"", "")
        return """{"print":{"sequence_id":"$seq","command":"project_file","param":"Metadata/plate_1.gcode","project_id":"0","profile_id":"0","task_id":"0","subtask_id":"0","subtask_name":"$bare","file":"","url":"$url","md5":"","timelapse":false,"bed_type":"auto","bed_levelling":true,"flow_cali":true,"vibration_cali":false,"layer_inspect":false,"ams_mapping":${if (amsSlot != null) "[$amsSlot]" else "\"\""},"use_ams":$useAms}}"""
    }

    /**
     * The same project print, spelled the other way.
     *
     * Two sources disagree and both are worth trying: OpenBambuAPI's wiki says `bed_levelling` and
     * `ams_mapping: ""`, while the widely used ha-bambulab integration (which demonstrably prints)
     * uses `bed_leveling: true` and `ams_mapping: [0]`. Rather than pick a winner from a chair, the
     * app tries one and, if the printer stays idle, the other.
     */
    fun projectFileAlt(seq: String, path: String, name: String, useAms: Boolean = true,
                       amsSlot: Int? = null): String {
        val safeName = name.replace("\"", "'")
        val bare = safeName.substringBeforeLast(".gcode").substringBeforeLast('.')
        val urlPath = if (path.startsWith("/")) path else "/cache/$path"
        return """{"print":{"sequence_id":"$seq","command":"project_file","param":"Metadata/plate_1.gcode","project_id":"0","profile_id":"0","task_id":"0","subtask_id":"0","subtask_name":"$bare","file":"","url":"ftp://$urlPath","md5":"","timelapse":false,"bed_type":"auto","bed_leveling":true,"flow_cali":true,"vibration_cali":false,"layer_inspect":false,"ams_mapping":${if (amsSlot != null) "[$amsSlot]" else "[0]"},"use_ams":$useAms}}"""
    }

    /** kept for the history list: same shape as any other project print */
    fun reprint(seq: String, file: String, param: String, name: String, useAms: Boolean = true): String {
        val safeName = name.replace("\"", "'")
        val safeFile = file.replace("\"", "")
        val safeParam = param.replace("\"", "")
        return """{"print":{"sequence_id":"$seq","command":"project_file","param":"$safeParam","file":"$safeFile","subtask_name":"$safeName","url":"","md5":"","timelapse":false,"bed_leveling":true,"flow_cali":true,"vibration_cali":false,"layer_inspect":false,"use_ams":$useAms}}"""
    }

    private fun printCmd(seq: String, command: String): String =
        """{"print":{"sequence_id":"$seq","command":"$command"}}"""

    /**
     * True only for a bare `pushing/pushall`. Anything else — a pause, a stop, a temperature
     * set, or a payload shape nobody has seen yet — is treated as a command.
     */
    fun isReadOnlySafe(payload: String): Boolean = runCatching {
        val root = JSONObject(payload)
        val keys = root.keys().asSequence().toList()
        keys == listOf("pushing") &&
            root.getJSONObject("pushing").optString("command") == "pushall" &&
            root.getJSONObject("pushing").keys().asSequence().toSet() == setOf("command", "sequence_id")
    }.getOrDefault(false)

    /**
     * The decision itself, as a pure function so it can be tested without Android or a socket:
     * null means "may be sent", a string means "refused, and this is why".
     */
    fun refuseIfReadOnly(readOnly: Boolean, payload: String): String? =
        if (readOnly && !isReadOnlySafe(payload)) READ_ONLY_REFUSAL else null

    const val READ_ONLY_REFUSAL = "read-only: this app does not send commands to the printer"
}
