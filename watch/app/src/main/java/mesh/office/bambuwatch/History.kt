package mesh.office.bambuwatch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Jobs the printer has reported, so the app is useful when nothing is printing.
 *
 * Everything here came from a report: the printer only knows *now*, so each transition into
 * printing opens record and each transition into a terminal state closes it. The file path is
 * captured when the printer gives one (it is empty when idle), because reprinting needs it.
 */
data class JobRecord(
    val name: String,
    val file: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val result: String? = null,
    val lastPercent: Int? = null,
    val palettes: Int = 0
) {
    val running: Boolean get() = endedAt == null
    /** reprint is only honest when the printer told us where the file is */
    val reprintable: Boolean get() = !file.isNullOrBlank()
    fun durationMin(): Int? = endedAt?.let { ((it - startedAt) / 60_000).toInt().coerceAtLeast(0) }
}

object History {
    const val MAX = 20

    private val PRINTING = setOf("RUNNING", "PREPARE", "SLICING")
    private val TERMINAL = setOf("FINISH", "FAILED")

    /**
     * Fold one state into the list. Pure, so the transition rules can be tested without a phone:
     * a job opens on the first printing report and closes on the first terminal one, and a state
     * that merely drifts (percent, layer) never opens a second record.
     */
    fun advance(jobs: List<JobRecord>, st: PrinterState, now: Long): List<JobRecord> {
        val g = st.gcodeState?.uppercase() ?: return jobs
        val list = jobs.toMutableList()
        val open = list.indexOfFirst { it.running }

        if (g in PRINTING) {
            if (open < 0) {
                list.add(0, JobRecord(
                    name = st.fileName ?: "unnamed job",
                    file = st.gcodeFile?.takeIf { it.isNotBlank() },
                    startedAt = now,
                    lastPercent = st.progress,
                    palettes = st.trays.count { it.isLoaded }
                ))
            } else {
                val cur = list[open]
                list[open] = cur.copy(
                    // the printer often reports the path or the name only once printing starts
                    name = st.fileName?.takeIf { it.isNotBlank() } ?: cur.name,
                    file = st.gcodeFile?.takeIf { it.isNotBlank() } ?: cur.file,
                    lastPercent = st.progress ?: cur.lastPercent
                )
            }
        } else if (g in TERMINAL) {
            if (open >= 0) {
                val cur = list[open]
                list[open] = cur.copy(
                    endedAt = now,
                    result = g,
                    lastPercent = st.progress ?: cur.lastPercent,
                    file = st.gcodeFile?.takeIf { it.isNotBlank() } ?: cur.file,
                    name = st.fileName?.takeIf { it.isNotBlank() } ?: cur.name
                )
            } else {
                // we only saw it finished: keep the fact without pretending to know the duration
                list.add(0, JobRecord(
                    name = st.fileName ?: "unnamed job",
                    file = st.gcodeFile?.takeIf { it.isNotBlank() },
                    startedAt = now,
                    endedAt = now,
                    result = g,
                    lastPercent = st.progress,
                    palettes = st.trays.count { it.isLoaded }
                ))
            }
        }
        return list.sortedByDescending { it.startedAt }.take(MAX)
    }

    fun toJson(jobs: List<JobRecord>): String = JSONArray().apply {
        jobs.forEach { j ->
            put(JSONObject().apply {
                put("name", j.name)
                j.file?.let { put("file", it) }
                put("started", j.startedAt)
                j.endedAt?.let { put("ended", it) }
                j.result?.let { put("result", it) }
                j.lastPercent?.let { put("pct", it) }
                put("palettes", j.palettes)
            })
        }
    }.toString()

    fun fromJson(text: String?): List<JobRecord> = runCatching {
        val arr = JSONArray(text ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                JobRecord(
                    name = o.optString("name", "unnamed job"),
                    file = o.optString("file", "").ifBlank { null },
                    startedAt = o.optLong("started", 0),
                    endedAt = o.optLong("ended", 0).takeIf { it > 0 },
                    result = o.optString("result", "").ifBlank { null },
                    lastPercent = o.optInt("pct", -1).takeIf { it >= 0 },
                    palettes = o.optInt("palettes", 0)
                )
            }
        }
    }.getOrDefault(emptyList())
}
