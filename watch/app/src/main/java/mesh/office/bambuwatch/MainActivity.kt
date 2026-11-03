package mesh.office.bambuwatch

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    init { ErrorText.load(app) }
    val printer = Printer(prefs, viewModelScope)
    val camera = Camera(prefs, viewModelScope)
    val card = Card(prefs, viewModelScope, reportedAddress = { printer.state.value.ip })

    var showSettings by mutableStateOf(!prefs.configured)
        private set

    var showFiles by mutableStateOf(false)
        private set
    var showSlicer by mutableStateOf(false)
    val slice = SliceState()

    fun openSettings() { showSettings = true }
    /** open on the cached listing; a pull-down or Refresh does the 18-second read */
    fun openFiles() { card.refresh(force = false); showFiles = true }
    fun closeFiles() { showFiles = false }
    fun openSlicer() { showSlicer = true }
    fun closeSlicer() { showSlicer = false }

    /**
     * Print a job the slicer has already put on the card. It goes through the same path as any
     * other card file, so the read-only gate applies: with read-only on, this is refused there.
     */
    fun printSliced(job: Slicer.Job) {
        val f = Slicer.cardFile(job)
        if (f == null) {
            slice.error = "that job is not on the card — send it again"
            return
        }
        // Read-only refuses every command that changes what the printer is doing, and a print is
        // the biggest one. Say that plainly instead of letting the refusal look like a silent
        // failure: the previous version navigated away whether or not anything was sent.
        if (prefs.readOnly) {
            slice.error = "read-only is on, so a print is refused. Settings → read-only off, then try again."
            return
        }
        val slot = slice.tray.toIntOrNull()
        val sent = printer.printCardFile(f, amsSlot = slot)
        if (!sent) {
            slice.error = printer.note.value ?: "the printer did not accept the print"
            return
        }
        slice.error = null
        showSlicer = false
    }
    fun refreshFiles() { card.refresh() }
    fun refreshFilesWithRoot() { rootListed = true; card.refresh(includeRoot = true) }

    var rootListed by mutableStateOf(false)
        private set

    fun clearJournal() { printer.clearJournal() }

    fun printCardFile(f: CardFile) {
        printer.printCardFile(f)
        // the answer to "did it start" is on the monitor, not on the file list
        showFiles = false
    }
    fun closeSettings() { showSettings = false }

    fun connectAll() {
        if (!prefs.configured) return
        printer.connect()
        camera.start()
    }

    /** called on ON_START: the OS may have taken the socket away while we were away */
    fun onForeground() {
        if (!prefs.configured) return
        printer.onForeground()
        camera.start()
    }

    /** called on ON_STOP: close deliberately, keep what we last knew */
    fun onBackground() {
        printer.onBackground()
        camera.stop()
    }

    fun save(host: String, code: String, serial: String, mqttPort: Int, camPort: Int, cardHost: String = "") {
        prefs.host = host
        prefs.accessCode = code
        prefs.serial = serial
        prefs.mqttPort = mqttPort
        prefs.camPort = camPort
        prefs.cardHost = cardHost
        printer.disconnect(); camera.stop()
        showSettings = false
        connectAll()
    }

    /** takes effect at once: the guard reads prefs on every publish */
    /** what the phone is actually running — a report of a bug is useless without it */
    fun appVersion(): String = runCatching {
        val pm = getApplication<Application>().packageManager
        pm.getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun setReadOnly(v: Boolean) { prefs.readOnly = v }

    fun reprintJob(job: JobRecord) { printer.reprint(job) }

    fun forgetPin() {
        prefs.clearPin("${prefs.host}:${prefs.mqttPort}")
        prefs.pendingPinReport = null
    }

    override fun onCleared() {
        printer.disconnect(); camera.stop()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // background/onBackground must be named here: Surface(contentColor = contentColorFor(color))
            // resolves to Unspecified for a colour that is not in the scheme, and the fallback
            // content colour is BLACK — which is how the title and the state word came out
            // near-black on a dark background.
            BwTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    val vm: MainViewModel = viewModel()
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.onForeground()
                                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.onBackground()
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    when {
                        vm.showSettings -> SettingsScreen(vm)
                        vm.showFiles -> FilesScreen(vm)
                        vm.showSlicer -> SliceScreen(
                            baseUrl = vm.prefs.slicerUrl,
                            state = vm.slice,
                            onPrint = { vm.printSliced(it) },
                            onClose = { vm.closeSlicer() },
                            readOnly = vm.prefs.readOnly,
                            trays = vm.printer.state.value.trays.filter { it.isLoaded },
                        )
                        else -> MonitorScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorScreen(vm: MainViewModel) {
    val st by vm.printer.state.collectAsState()
    val note by vm.printer.note.collectAsState()
    val seen by vm.printer.reportsSeen.collectAsState()
    val history by vm.printer.history.collectAsState()
    val journal by vm.printer.journal.collectAsState()
    val pinIssue by remember { mutableStateOf(vm.prefs.pendingPinReport) }
    val bmp by vm.camera.frame.collectAsState()
    val camNote by vm.camera.note.collectAsState()
    val camDiag by vm.camera.diag.collectAsState()

    LaunchedEffect(Unit) {
        while (true) { delay(30_000); vm.printer.refresh() }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(if (st.connected) Bw.ok else Bw.fg3))
                Text("Bambu Watch", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    letterSpacing = (-0.2).sp)
                Spacer(Modifier.weight(1f))
                BarAction("Files") { vm.openFiles() }
                BarAction("Slice") { vm.openSlicer() }
                BarAction("Settings") { vm.openSettings() }
        }

        StatusChip(st, vm.prefs.readOnly)

        // in-app only, by Václav's decision: no notifications, no foreground service. Whatever is
        // wrong is shouting here, and the app costs nothing while it is closed.
        val alerts = remember(st, seen) { Alerts.build(st) }
        alerts.firstOrNull()?.let { a -> AlertBanner(a) }

        // Controls exist only when read-only is off, and stop always asks first: this screen is
        // read next to a camera, and a stop is the one action that throws work away.
        if (!vm.prefs.readOnly && st.gcodeState != null) {
            var confirmStop by remember { mutableStateOf(false) }
            val g = st.gcodeState!!.uppercase()
            val active = g in setOf("RUNNING", "PREPARE", "PAUSE", "SLICING")
            if (active) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (g == "PAUSE") {
                        Button(onClick = { vm.printer.resume() }) { Text("Resume") }
                    } else {
                        Button(onClick = { vm.printer.pause() }) { Text("Pause") }
                    }
                    OutlinedButton(onClick = { confirmStop = true }) {
                        Text("Stop", color = Bw.bad)
                    }
                }
            }
            if (confirmStop) {
                AlertDialog(
                    onDismissRequest = { confirmStop = false },
                    title = { Text("Stop the print?") },
                    text = { Text("The printer abandons the job in progress. Pause is reversible; stop is not.") },
                    confirmButton = {
                        TextButton(onClick = { confirmStop = false; vm.printer.stop() }) {
                            Text("Stop the print", color = Bw.bad)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmStop = false }) { Text("Keep printing") }
                    }
                )
            }
        }

        // the hero: what is happening, how far, how long. Everything else on this screen is
        // secondary and sits below it.
        if (st.progress != null || st.fileName != null) {
            val tint = when (st.statusWord) {
                "printing", "preparing" -> Bw.ok
                "paused" -> Bw.warn
                "error" -> Bw.bad
                "finished" -> Bw.acc
                else -> Bw.fg3
            }
            HeroSurface(tint = tint) {
                st.fileName?.let {
                    Text(it, fontSize = 13.sp, color = Bw.fg2, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Text(
                        st.progress?.let { "$it" } ?: "—",
                        fontSize = 42.sp, fontWeight = FontWeight.SemiBold, color = Bw.fg,
                        letterSpacing = (-1).sp, fontFamily = FontFamily.Monospace)
                    if (st.progress != null) Text("%", fontSize = 16.sp, color = Bw.fg3,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    val l = st.layer; val t = st.totalLayers
                    if (l != null) Text(
                        if (t != null) "layer $l / $t" else "layer $l",
                        fontSize = 12.sp, color = Bw.fg3, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                st.progress?.let { ProgressBar(it) }
                val parts = listOfNotNull(
                    st.remainingMin?.takeIf { it > 0 }?.let { humanTime(it) },
                    st.gcodeState?.lowercase()
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 12.sp,
                    color = Bw.fg3, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Temps(st)

        CameraPane(bmp, camNote, camDiag)

        if (st.trays.isNotEmpty()) Ams(st)

        // shown only once something has been sent: a record that survives every status refresh,
        // so "nothing happened" can be explained after the fact rather than guessed at
        if (journal.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Bw.surface, contentColor = Bw.fg)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("What I asked the printer", fontSize = 12.sp, color = Bw.fg2,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearJournal() }) { Text("Clear", fontSize = 11.sp) }
                    }
                    journal.forEach {
                        Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = if (it.contains("reply") || it.contains("gave up") || it.contains("failed"))
                                Bw.warn else Bw.fg2)
                    }
                }
            }
        }

        if (history.isNotEmpty()) JobsCard(history, vm.prefs.readOnly,
            onReprint = { vm.reprintJob(it) })

        if (st.printError != null || st.hms.isNotEmpty()) Problems(st)

        if (pinIssue != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Bw.soft(Bw.bad), contentColor = Bw.fg)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Certificate changed", fontWeight = FontWeight.Bold)
                    Text(pinIssue!!, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Not connected. If the printer was replaced or re-flashed, clear the pin in Settings.",
                        fontSize = 12.sp)
                }
            }
        }

        note?.let { Text(it, fontSize = 12.sp, color = Bw.warn) }
        if (note == null && !st.connected) {
            val asOf = st.asOf
            Text(
                if (asOf != null) "last report ${Age.at(asOf)} (${Age.label(System.currentTimeMillis(), asOf)}) — reconnecting…"
                else "connecting…",
                fontSize = 12.sp, color = Bw.fg2)
        }
        // always visible: "mqtt reports: N" distinguishes "nothing arriving" from "arriving but not parsed"
        Text("mqtt reports: $seen", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = Bw.fg3)
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    Box(Modifier.height(38.dp).clip(Bw.chipShape).background(Bw.surface)
            .border(1.dp, Bw.line, Bw.chipShape)
            .clickable { onClick() }
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, color = Bw.fg2, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusChip(st: PrinterState, readOnly: Boolean) {
    val (label, color) = when (st.statusWord) {
        "printing" -> "Printing" to Color(0xFF2E7D32)
        "paused" -> "Paused" to Color(0xFFB26A00)
        "finished" -> "Finished" to Color(0xFF1565C0)
        "error" -> "Error" to Color(0xFFB3261E)
        "preparing" -> "Preparing" to Color(0xFF2E7D32)
        "idle" -> "Idle" to Bw.line
        "offline" -> "Offline" to Bw.line
        "unknown" -> "Connected" to Bw.line
        else -> st.statusWord.replaceFirstChar { it.uppercase() } to Bw.line
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Bw.soft(color))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp, color = color)
        }
        st.gcodeState?.let {
            if (it.uppercase() != label.uppercase()) Text(it.lowercase(), fontSize = 12.sp, color = Bw.fg3)
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (readOnly) "read-only" else "CONTROL",
            fontSize = 10.sp,
            color = if (readOnly) Bw.fg3 else Bw.warn,
            fontWeight = if (readOnly) FontWeight.Normal else FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(if (readOnly) Color(0xFF23262B) else Color(0xFF3A2E1B))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CameraPane(bmp: android.graphics.Bitmap?, note: String?, diag: String?) {
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(Bw.cardShape)
            .background(Color(0xFF1A1D22)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "printer camera",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(note ?: "no camera frame", fontSize = 12.sp, color = Bw.fg3, modifier = Modifier.padding(12.dp))
        }
        // small caption in the corner: proves the stream is alive and reports oddities
        diag?.let {
            Text(
                it,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFCBD5E1),
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color(0xB3000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ProgressBar(percent: Int) {
    val p = (percent.coerceIn(0, 100)) / 100f
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2F37))) {
        Box(Modifier.fillMaxWidth(p).height(8.dp).background(Bw.acc))
    }
}

@Composable
private fun Temps(st: PrinterState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        TempTile("Nozzle", st.nozzle, st.nozzleTarget, Modifier.weight(1f))
        TempTile("Bed", st.bed, st.bedTarget, Modifier.weight(1f))
        if (st.hasChamber) TempTile("Chamber", st.chamber, null, Modifier.weight(1f))
    }
}

@Composable
private fun TempTile(label: String, value: Double?, target: Double? = null,
                     modifier: Modifier = Modifier) {
    Column(modifier.background(Bw.surface, Bw.cardShape).border(1.dp, Bw.line, Bw.cardShape)
            .padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, color = Bw.fg3,
            fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp)
        Text(value?.let { "%.0f°".format(it) } ?: "—",
            fontSize = 21.sp, color = Bw.fg, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                target != null && target > 0.5 -> "→ %.0f°".format(target)
                (value ?: 0.0) < 60.0 -> "cooling"
                else -> "steady"
            },
            fontSize = 11.sp, color = Bw.fg3, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AlertBanner(a: Alert) {
    val (bg, fg) = when (a.severity) {
        Alert.Severity.BAD -> Bw.soft(Bw.bad) to Bw.bad
        Alert.Severity.WARN -> Bw.soft(Bw.warn) to Bw.warn
        Alert.Severity.INFO -> Bw.soft(Bw.acc) to Bw.acc
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.title, fontWeight = FontWeight.SemiBold)
            a.detail.take(4).forEach { Text(it, fontSize = 12.sp, color = fg.copy(alpha = 0.85f),
                fontFamily = if (it.startsWith("HMS") || it.startsWith("print_error")) FontFamily.Monospace else FontFamily.Default) }
            if (a.severity != Alert.Severity.INFO) {
                Text(if (a.detail.any { it.startsWith("HMS") || it.startsWith("print_error") }) Codes.UNKNOWN else "",
                    fontSize = 10.sp, color = fg.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun JobsCard(jobs: List<JobRecord>, readOnly: Boolean, onReprint: (JobRecord) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Bw.surface, contentColor = Bw.fg)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent prints", fontSize = 12.sp, color = Bw.fg2)
            jobs.take(4).forEach { j ->
                var confirm by remember(j.startedAt) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val bits = listOfNotNull(
                            j.result ?: if (j.running) "printing" else null,
                            j.durationMin()?.let { "$it min" },
                            j.lastPercent?.let { "$it %" }
                        )
                        Text(bits.joinToString(" · "), fontSize = 11.sp, color = Bw.fg2)
                    }
                    if (!readOnly && j.reprintable && !j.running) {
                        TextButton(onClick = { confirm = true }) { Text("Reprint") }
                    }
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Print “${j.name}” again?") },
                        text = { Text("The printer starts the same file from its SD card. It uses filament and " +
                            "takes as long as it takes; this app cannot see the file's contents to check it.") },
                        confirmButton = { TextButton(onClick = { confirm = false; onReprint(j) }) { Text("Start printing") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
                    )
                }
            }
            if (readOnly) Text("Reprint needs read-only mode off (Settings).",
                fontSize = 10.sp, color = Bw.fg3)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FilesScreen(vm: MainViewModel) {
    val files by vm.card.files.collectAsState()
    val busy by vm.card.busy.collectAsState()
    val note by vm.card.note.collectAsState()
    val progress by vm.card.progress.collectAsState()
    val readAt by vm.card.readAt.collectAsState()
    val readOnly = vm.prefs.readOnly

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = busy,
        onRefresh = { vm.refreshFiles() },
        modifier = Modifier.fillMaxSize().safeDrawingPadding()
    ) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Files on the card", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refreshFiles() }, enabled = !busy) { Text("Refresh") }
            TextButton(onClick = { vm.closeFiles() }) { Text("Back") }
        }
        Text("Projects (.3mf) are the files the printer starts reliably. A raw .gcode is a toolpath " +
             "without the project around it, and this printer often refuses it.",
            fontSize = 10.sp, color = Bw.fg3)
        Text("Pull down to re-read. Only the folder Studio prints from is read; the card's root is slow.",
            fontSize = 10.sp, color = Bw.fg3)
        if (readAt > 0) Text("read at ${Age.at(readAt)} (${Age.label(System.currentTimeMillis(), readAt)})",
            fontSize = 10.sp, color = Bw.line, fontFamily = FontFamily.Monospace)
        Text("app ${vm.appVersion()} · card at ${vm.prefs.cardHost.ifBlank { vm.prefs.host }}:990",
            fontSize = 10.sp, color = Bw.line, fontFamily = FontFamily.Monospace)
        Text("Listed over FTPS on port 990 with the same access code. Tap a file to print it.",
            fontSize = 11.sp, color = Bw.fg2)
        if (readOnly) Text("Read-only mode is on, so printing a file is refused. Turn it off in Settings.",
            fontSize = 11.sp, color = Bw.warn)

        if (busy) Text("reading the card… $progress files so far",
            fontSize = 12.sp, color = Bw.fg2)
        if (!busy && files != null && files!!.isNotEmpty() && !vm.rootListed) {
            TextButton(onClick = { vm.refreshFilesWithRoot() }) { Text("Also read the card's root folder") }
        }
        note?.let { Text(it, fontSize = 11.sp, color = Bw.warn) }

        files?.takeIf { it.isNotEmpty() && !busy }?.let { list ->
            // Václav's actual use (23-10): print the thing he sliced and forgot, so the screen
            // opens on the newest files rather than on all 578.
            var showAll by remember { mutableStateOf(false) }
            val shown = if (showAll) list else list.take(20)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAll) "${list.size} files" else "newest ${shown.size} of ${list.size}",
                    fontSize = 11.sp, color = Bw.fg3, modifier = Modifier.weight(1f))
                if (list.size > shown.size || showAll) TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Newest only" else "Show all ${list.size}")
                }
            }
            shown.forEach { f ->
                var confirm by remember(f.path) { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = Bw.surface,
                    contentColor = Bw.fg)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!Commands.looksPrintable(f.name))
                                Text("raw toolpath — the printer may refuse to start it directly",
                                    fontSize = 10.sp, color = Bw.warn)
                            Text(
                                CardFile.humanSize(f.size) +
                                    (if (f.modified > 0) " · ${Age.at(f.modified)}" else ""),
                                fontSize = 11.sp, color = Bw.fg2, fontFamily = FontFamily.Monospace)
                        }
                        if (!readOnly) OutlinedButton(onClick = { confirm = true }) { Text("Print") }
                    }
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Print ${f.name}?") },
                        text = { Text("The printer starts this file from its card, using filament. " +
                            "This app cannot see inside the file to check what it will do.") },
                        confirmButton = { TextButton(onClick = { confirm = false; vm.printCardFile(f) }) {
                            Text("Start printing") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
                    )
                }
            }
        }
    }
}
    }

@Composable
private fun Ams(st: PrinterState) {
    Card(colors = CardDefaults.cardColors(containerColor = Bw.surface, contentColor = Bw.fg)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AMS", fontSize = 12.sp, color = Bw.fg2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                st.trays.forEach { tray ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (tray.isLoaded) parseColour(tray.colorHex) else Bw.surface2)
                                .then(if (tray.active) Modifier.padding(0.dp) else Modifier)
                        )
                        Text(
                            if (tray.isLoaded) tray.material!! else "empty",
                            fontSize = 11.sp,
                            color = if (tray.isLoaded) Color.Unspecified else Bw.fg3,
                            fontWeight = if (tray.active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // a percentage only means something when there is filament to run out of
                        if (tray.isLoaded) Text(
                            tray.remain?.let { "$it%" } ?: "—",
                            fontSize = 11.sp, color = Bw.fg2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Problems(st: PrinterState) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2318), contentColor = Bw.fg)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Printer reports a problem", fontWeight = FontWeight.Bold)
            st.printError?.let {
                Text(ErrorText.forPrintError(it) ?: "print_error $it", fontSize = 13.sp)
                Text("print_error $it", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Bw.fg3)
            }
            st.hms.forEach { key ->
                val text = ErrorText.forHms(key)
                if (text != null) {
                    Text(text, fontSize = 13.sp)
                    Text("HMS $key", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Bw.fg3)
                } else {
                    Text("HMS $key", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Text("Bambu's own words, from the 5,943-code table shipped in the app.",
                fontSize = 11.sp, color = Color(0xFFC9A38C))
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    var host by remember { mutableStateOf(vm.prefs.host) }
    var code by remember { mutableStateOf(vm.prefs.accessCode) }
    var serial by remember { mutableStateOf(vm.prefs.serial) }
    var mqttPort by remember { mutableStateOf(vm.prefs.mqttPort.toString()) }
    var camPort by remember { mutableStateOf(vm.prefs.camPort.toString()) }
    var cardHost by remember { mutableStateOf(vm.prefs.cardHost) }
    var readOnly by remember { mutableStateOf(vm.prefs.readOnly) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Bambu Watch ${vm.appVersion()}", fontSize = 11.sp, color = Bw.fg3,
            fontFamily = FontFamily.Monospace)
        Text("Name or IP of the printer. It does not have to be on this phone's LAN — any address that routes works.",
            fontSize = 12.sp, color = Bw.fg2)
        OutlinedTextField(host, { host = it }, label = { Text("Printer address") }, singleLine = true,
            placeholder = { Text("printer.lan.proteus.office.mesh") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("Access code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Stored encrypted on this device. It is not logged and not sent anywhere except the printer.",
            fontSize = 11.sp, color = Bw.fg3)
        OutlinedTextField(serial, { serial = it }, label = { Text("Serial (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Left empty, the app learns it from the first report or from the printer certificate.",
            fontSize = 11.sp, color = Bw.fg3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(mqttPort, { mqttPort = it.filter(Char::isDigit) }, label = { Text("MQTT port") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
            OutlinedTextField(camPort, { camPort = it.filter(Char::isDigit) }, label = { Text("Camera port") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Read-only mode", fontWeight = FontWeight.Medium)
                Text(
                    if (readOnly)
                        "On: the app asks the printer to report and sends nothing else. It cannot start, pause or stop a print."
                    else
                        "Off: pause and stop appear on the main screen. Commands act on the real print at once.",
                    fontSize = 11.sp,
                    color = if (readOnly) Bw.fg3 else Bw.warn
                )
            }
            Switch(checked = readOnly, onCheckedChange = { readOnly = it; vm.setReadOnly(it) })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Bw.surface2))

        OutlinedTextField(cardHost, { cardHost = it }, label = { Text("Card address (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("192.168.1.50") })
        Text("Used only for the SD-card listing (FTPS 990). Leave empty to use the address above. " +
             "Set the printer's own IP here if the address above is a mesh name or a forwarded one: " +
             "FTP needs a random data port, which a forwarder usually cannot pass.",
            fontSize = 11.sp, color = Bw.fg3)

        val pinned = vm.prefs.pin("$host:${mqttPort}")
        Text("Certificate pin: ${pinned ?: "not pinned yet"}", fontSize = 11.sp,
            color = Bw.fg3, fontFamily = FontFamily.Monospace)
        vm.prefs.pendingPinReport?.let {
            Text(it, fontSize = 11.sp, color = Bw.warn, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { vm.forgetPin() }) { Text("Accept the new certificate") }
        }
        Button(
            onClick = {
                vm.save(host, code, serial,
                    mqttPort.toIntOrNull() ?: 8883, camPort.toIntOrNull() ?: 6000)
            },
            enabled = host.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save and connect") }
        if (vm.prefs.configured) TextButton(onClick = { vm.closeSettings() }) { Text("Cancel") }
    }
}

private fun humanTime(min: Int): String {
    if (min <= 0) return "—"
    val h = min / 60; val m = min % 60
    return if (h > 0) "${h} h ${m} min remaining" else "$m min remaining"
}

private fun parseColour(hex: String?): Color {
    if (hex == null) return Bw.line
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Bw.line)
}
