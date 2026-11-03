package mesh.office.bambuwatch

/**
 * What the screen should shout about, derived from the state we already have.
 *
 * Pure on purpose: "highlight when something is going on" is a rule, and rules are worth testing.
 * Václav's constraint (23-10): in-app only. No notifications, no foreground service, no battery
 * tax — an app that buzzes is an app that gets muted, and he checks when a print matters.
 */
data class Alert(val severity: Severity, val title: String, val detail: List<String> = emptyList()) {
    enum class Severity { BAD, WARN, INFO }
}

object Alerts {

    /** below this, a spool is going to run out during a print often enough to matter */
    const val LOW_FILAMENT_PCT = 10

    fun build(st: PrinterState, now: Long = System.currentTimeMillis()): List<Alert> {
        val out = mutableListOf<Alert>()

        if (st.printError != null && st.printError != 0L) {
            out += Alert(
                Alert.Severity.BAD,
                "Printer reports an error",
                listOf("print_error ${st.printError}") + Codes.explain("print_error:${st.printError}").orEmpty()
            )
        }

        if (st.hms.isNotEmpty()) {
            out += Alert(
                Alert.Severity.BAD,
                if (st.hms.size == 1) "Printer reports a fault" else "Printer reports ${st.hms.size} faults",
                st.hms.flatMap { h -> listOf(h) + Codes.explain("hms:$h").orEmpty() }
            )
        }

        val low = st.trays.filter { it.isLoaded && it.remain != null && it.remain!! < LOW_FILAMENT_PCT }
        if (low.isNotEmpty()) {
            out += Alert(
                Alert.Severity.WARN,
                if (low.size == 1) "Filament low" else "Filament low in ${low.size} slots",
                low.map { "slot ${it.slot}: ${it.material ?: "?"} ${it.remain}%" }
            )
        }

        if (st.gcodeState?.uppercase() == "FAILED") {
            out += Alert(Alert.Severity.BAD, "The print failed")
        }

        // connected earlier and then went quiet: worth saying, but not alarming
        val asOf = st.asOf
        if (asOf != null && !st.connected) {
            out += Alert(
                Alert.Severity.WARN,
                "No fresh data",
                listOf("last report ${Age.at(asOf)} (${Age.label(now, asOf)})")
            )
        }

        if (st.gcodeState?.uppercase() == "PAUSE") {
            out += Alert(Alert.Severity.INFO, "Print is paused")
        }

        return out
    }
}

/**
 * Bambu's own codes, in words — and where the words are not known, the code alone.
 *
 * Deliberately empty: BBL publishes no stable offline table, the wiki's code lists are served by
 * a JS app that I could not parse, and inventing plausible meanings for codes somebody reads at
 * 2 a.m. is worse than admitting ignorance. The lookup is here so a sourced table can be dropped
 * in; every entry must carry where it came from.
 */
object Codes {

    data class Meaning(val text: String, val source: String)

    fun explain(key: String): List<String>? = ErrorText.explain(key)?.let { listOf(it) }

    /** shown next to an unexplained code, so silence is not mistaken for "fine" */
    const val UNKNOWN =
        "No meaning for that code in the bundled table; the number is Bambu's, not ours."

    fun tableSize(): Int = ErrorText.size()
    fun provenance(): String =
        "ha-bambulab hms_en.json (generated from Bambu's own strings); 5,944 codes, bundled offline"
}
