package mesh.office.bambuwatch

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SliceBg = Bw.bg
private val SliceCard = Bw.surface
private val SliceFg = Bw.fg
private val SliceDim = Bw.fg2
private val SliceAcc = Bw.acc
private val SliceOk = Bw.ok
private val SliceWarn = Bw.warn
private val SliceBad = Bw.bad

/** "RRGGBB" or "RRGGBBAA" as the printer reports it */
private fun trayColour(hex: String?): Color = runCatching {
    val h = (hex ?: "").trim().removePrefix("#")
    if (h.length < 6) SliceDim else Color(("FF" + h.take(6)).toLong(16))
}.getOrDefault(SliceDim)

private val featureColours = mapOf(
    "Outer wall" to 0xFF6FB8FF.toInt(),
    "Overhang wall" to 0xFFFF9E64.toInt(),
    "Inner wall" to 0xFF8AA6C8.toInt(),
    "Top surface" to 0xFFE0A030.toInt(),
    "Bottom surface" to 0xFFB8842A.toInt(),
    "Sparse infill" to 0xFF4A5460.toInt(),
    "Internal solid infill" to 0xFF5F6C7C.toInt(),
    "Bridge" to 0xFFD46A8A.toInt(),
    "Gap infill" to 0xFF555D68.toInt(),
    "Skirt" to 0xFF3A424D.toInt(),
    "Support" to 0xFF506B58.toInt(),
    "Support interface" to 0xFF6A8A72.toInt(),
    "Floating vertical shell" to 0xFF777F8C.toInt(),
    "Custom" to 0xFF4C8DFF.toInt(),
)
private const val DEFAULT_COLOUR = 0xFF626B78.toInt()

/**
 * Everything the slicer screen needs. Kept in one place so the screen is a pure draw of state.
 */
class SliceState {
    /**
     * Work that must survive the screen. Uploading, slicing and polling take minutes; a scope from
     * `rememberCoroutineScope()` is cancelled the moment SliceScreen leaves the composition, and
     * Compose 1.7 then throws "The coroutine scope left the composition" when the work resumes.
     * This one belongs to the view model, which is as long-lived as the activity.
     */
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
    )

    var busy by mutableStateOf(false)
    // the settings for the next upload, or for re-slicing an open job
    var process by mutableStateOf("")
    var filament by mutableStateOf("")
    var copies by mutableStateOf("1")
    var tiltX by mutableStateOf("0")
    var tiltY by mutableStateOf("0")
    var scale by mutableStateOf("1")
    /** the AMS slot to print from: "" means let the printer choose */
    var tray by mutableStateOf("")
    var presetsLoaded by mutableStateOf(false)

    fun applyParams(p: Map<String, String>) {
        process = p["process"] ?: process
        filament = p["filament"] ?: filament
        copies = p["copies"]?.takeIf { it.isNotBlank() } ?: "1"
        tiltX = p["rx"]?.takeIf { it.isNotBlank() } ?: "0"
        tiltY = p["ry"]?.takeIf { it.isNotBlank() } ?: "0"
        scale = p["scale"]?.takeIf { it.isNotBlank() } ?: "1"
        tray = p["tray"] ?: ""
    }

    fun params(): Map<String, String> = mapOf(
        "process" to process, "filament" to filament,
        "copies" to copies, "rx" to tiltX, "ry" to tiltY, "scale" to scale,
        "tray" to tray,
    )
    var error by mutableStateOf<String?>(null)
    var jobs by mutableStateOf<List<Slicer.Job>>(emptyList())
    var presets by mutableStateOf<Slicer.Presets?>(null)
    var open by mutableStateOf<Slicer.Job?>(null)
    var toolpath by mutableStateOf<Toolpath?>(null)
    var note by mutableStateOf<String?>(null)
}

@Composable
fun SliceScreen(baseUrl: String, state: SliceState, onPrint: (Slicer.Job) -> Unit,
                onClose: () -> Unit, readOnly: Boolean = true,
                trays: List<Tray> = emptyList()) {
    val scope = state.scope        // survives leaving the screen; see SliceState.scope
    val ctx = LocalContext.current
    var pickerFor by remember { mutableStateOf<String?>(null) }

    // presets once, so the pickers have something in them
    LaunchedEffect(baseUrl) {
        if (state.presetsLoaded) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { Slicer.presets(baseUrl) } }
            .onSuccess {
                state.presets = it
                if (state.process.isBlank()) state.process = it.defaultProcess
                if (state.filament.isBlank()) state.filament = it.defaultFilament
                state.presetsLoaded = true
            }
            .onFailure { state.error = it.message ?: it.toString() }
    }
    LaunchedEffect(Unit) {
        if (state.jobs.isEmpty()) {
            runCatching { withContext(Dispatchers.IO) { Slicer.jobs(baseUrl) } }
                .onSuccess { if (!state.busy) state.jobs = it }
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        val want = pickerFor
        pickerFor = null
        if (uri == null || want == null) return@rememberLauncherForActivityResult
        scope.launch {
            state.busy = true
            state.error = null
            try {
                val name = resolveName(ctx, uri)
                val tmp = File(ctx.cacheDir, name)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                    } ?: throw java.io.IOException("could not read that file")
                    val chosen = state.params().toMutableMap()
                    if (chosen["process"].isNullOrBlank()) chosen["process"] = state.presets?.defaultProcess ?: ""
                    if (chosen["filament"].isNullOrBlank()) chosen["filament"] = state.presets?.defaultFilament ?: ""
                    val job = Slicer.upload(baseUrl, tmp, chosen)
                    pollUntilDone(state, baseUrl, job)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                      // leaving the screen is not a failure to report
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = e.message ?: e.toString()
            } finally {
                state.busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(SliceBg).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Slice", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SliceFg)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Monitor", color = SliceAcc) }
        }
        Text(baseUrl, fontSize = 11.sp, color = SliceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)

        state.open?.let { job ->
            Spacer(Modifier.height(12.dp))
            JobDetail(baseUrl, state, job, onPrint, readOnly, trays)
        } ?: run {
            Spacer(Modifier.height(12.dp))
            SettingsCard(state, trays) { }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickerFor = "new"; picker.launch(arrayOf("*/*")) },
                    enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(if (state.busy) "working…" else "Upload and slice")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        state.busy = true; state.error = null
                        try {
                            withContext(Dispatchers.IO) {
                                state.presets = Slicer.presets(baseUrl)
                                state.jobs = Slicer.jobs(baseUrl)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            state.error = e.message ?: e.toString()
                        } finally { state.busy = false }
                    }
                }, enabled = !state.busy) { Text("Refresh") }
            }
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = SliceBad, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("RECENT", fontSize = 11.sp, color = SliceDim, letterSpacing = 1.sp)
            if (state.jobs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(if (state.busy) "…" else "nothing yet — upload a model", color = SliceDim, fontSize = 13.sp)
            }
            state.jobs.forEach { j ->
                JobRow(j) {
                    scope.launch {
                        state.busy = true; state.error = null
                        try {
                            val full = withContext(Dispatchers.IO) { Slicer.job(baseUrl, j.id) }
                            state.open = full
                            state.applyParams(full.params)
                            state.note = null
                            state.toolpath = if (full.state == "sliced" || full.state == "ready") {
                                withContext(Dispatchers.IO) { runCatching { Slicer.toolpath(baseUrl, full.id) }.getOrNull() }
                            } else null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            state.error = e.message ?: e.toString()
                        } finally { state.busy = false }
                    }
                }
            }
        }
    }
}

/**
 * Quality, filament, copies, tilt and scale. The slicer takes these per job, so the same controls
 * serve a fresh upload and a re-slice of something already sliced.
 */
@Composable
private fun SettingsCard(state: SliceState, trays: List<Tray>, onChanged: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SliceCard, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("SETTINGS", fontSize = 11.sp, color = SliceDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))

        Dropdown("quality", state.process, "loading…", state.presets?.process.orEmpty()) {
            state.process = it; onChanged()
        }
        Dropdown("filament", state.filament, "loading…", state.presets?.filament.orEmpty()) {
            state.filament = it; onChanged()
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("copies", state.copies, Modifier.weight(1f)) { state.copies = it; onChanged() }
            NumberField("tilt X°", state.tiltX, Modifier.weight(1f)) { state.tiltX = it; onChanged() }
            NumberField("tilt Y°", state.tiltY, Modifier.weight(1f)) { state.tiltY = it; onChanged() }
            NumberField("scale", state.scale, Modifier.weight(1f)) { state.scale = it; onChanged() }
        }
        Text("A tilt is supported; a Z turn is not — this slicer cannot apply one.",
            color = SliceDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        TrayPicker(state, trays) { onChanged() }
    }
}

/**
 * Which spool to print from. The slicer puts the choice into `ams_mapping`, so the printer uses
 * THAT slot instead of picking one. Without it a mixed AMS is a lottery: PETG in slot 0, PLA in
 * 1-3, and a PLA job can end up on the PETG spool at PLA temperatures.
 */
@Composable
private fun TrayPicker(state: SliceState, trays: List<Tray>, onChanged: () -> Unit) {
    Text("FILAMENT SLOT", fontSize = 10.sp, color = SliceDim, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // "auto" first: the printer decides, which is what older versions did
        Surface(
            color = if (state.tray.isBlank()) SliceAcc.copy(alpha = 0.25f) else SliceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable { state.tray = ""; onChanged() }.padding(1.dp)
        ) {
            Text("auto", fontSize = 12.sp, color = SliceFg,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        }
        trays.forEach { tray ->
            val chosen = state.tray == tray.slot
            val colour = trayColour(tray.colorHex)
            Column(
                Modifier.weight(1f)
                    .background(if (chosen) SliceAcc.copy(alpha = 0.22f) else SliceCard, RoundedCornerShape(8.dp))
                    .then(if (chosen) Modifier.border(1.dp, SliceAcc, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { state.tray = tray.slot; onChanged() }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(16.dp).background(colour, RoundedCornerShape(4.dp)))
                Text(tray.slot, fontSize = 11.sp, color = SliceFg)
                Text(tray.material ?: "empty", fontSize = 9.sp, color = SliceDim)
            }
        }
        if (trays.isEmpty()) {
            Text("AMS not reported yet", fontSize = 11.sp, color = SliceDim)
        }
    }

    // the guard: a PLA job must not be pointed at the PETG spool
    val chosen = trays.firstOrNull { it.slot == state.tray }
    val jobKind = when {
        state.filament.contains("PETG", true) -> "PETG"
        state.filament.contains("PLA", true) -> "PLA"
        state.filament.contains("ABS", true) -> "ABS"
        else -> null
    }
    if (chosen != null && jobKind != null && chosen.material != null &&
        !chosen.material!!.contains(jobKind, true)) {
        Text("That slot holds ${chosen.material}, but this job is sliced for $jobKind. " +
             "Wrong temperatures — pick a $jobKind slot.",
            color = SliceWarn, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Chevron(down: Boolean = true) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            if (down) { moveTo(w * 0.2f, h * 0.38f); lineTo(w * 0.5f, h * 0.68f); lineTo(w * 0.8f, h * 0.38f) }
            else { moveTo(w * 0.2f, h * 0.62f); lineTo(w * 0.5f, h * 0.32f); lineTo(w * 0.8f, h * 0.62f) }
        }
        drawPath(p, color = Bw.fg3, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.8f.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun FieldLabel(text: String) = Text(
    text.uppercase(), fontSize = 10.sp, color = Bw.fg3, letterSpacing = 0.9.sp,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

/** a field, not a giant outlined button: 48 dp, surface fill, the value in normal text */
@Composable
private fun Field(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    FieldLabel(label)
    Row(Modifier.fillMaxWidth().height(48.dp)
            .background(Bw.surface2, Bw.fieldShape)
            .border(1.dp, Bw.line, Bw.fieldShape)
            .clickable { onClick() }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (value.isBlank()) placeholder else value.substringBefore(".json"),
            fontSize = 13.sp, color = if (value.isBlank()) Bw.fg3 else Bw.fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Chevron()
    }
}

@Composable
private fun Dropdown(label: String, value: String, blank: String,
                     options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Field(label, value, blank) { open = true }
        Box(Modifier.fillMaxWidth()) {
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                options.forEach { item ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(item.substringBefore(".json"), fontSize = 13.sp,
                            color = if (item == value) Bw.acc else Bw.fg) },
                        onClick = { onPick(item); open = false })
                }
            }
        }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier,
                        onValue: (String) -> Unit) {
    Column(modifier) {
        FieldLabel(label)
        Box(Modifier.fillMaxWidth().height(48.dp)
                .background(Bw.surface2, Bw.fieldShape)
                .border(1.dp, Bw.line, Bw.fieldShape),
            contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { v -> onValue(v.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp, color = Bw.fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun JobRow(job: Slicer.Job, onClick: () -> Unit) {
    val tint = when {
        job.state == "failed" -> SliceBad
        job.ready -> SliceOk
        job.busy -> SliceWarn
        else -> SliceDim
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(SliceCard, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Bw.surface2, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(
                when {
                    job.state == "failed" -> Bw.bad
                    job.state == "ready" -> Bw.ok
                    job.busy -> Bw.warn
                    else -> Bw.fg3
                }))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, color = SliceFg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    job.state,
                    job.seconds?.let { fmtTime(it) },
                    job.grams?.let { "%.2f g".format(it) },
                    job.params["copies"]?.takeIf { it != "1" && it.isNotBlank() }?.let { "$it copies" },
                ).joinToString(" · "),
                color = tint, fontSize = 12.sp
            )
        }
        Text(">", color = SliceDim, fontSize = 16.sp)
    }
}

@Composable
private fun JobDetail(baseUrl: String, state: SliceState, job: Slicer.Job,
                      onPrint: (Slicer.Job) -> Unit, readOnly: Boolean,
                      trays: List<Tray> = emptyList()) {
    val scope = state.scope
    var confirmSend by remember(job.id) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(job.name, color = SliceFg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.open = null; state.toolpath = null }) { Text("Back", color = SliceAcc) }
    }
    state.toolpath?.let { tp ->
        ToolpathView(tp, Modifier.fillMaxWidth().height(320.dp).padding(top = 6.dp))
        Text(tp.box.size(), color = SliceDim, fontSize = 12.sp)
    } ?: run {
        // a failed job has a reason; say it instead of describing the absence of a picture
        if (job.state == "failed") {
            Text("this slice failed", color = SliceBad, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(job.error ?: "the slicer did not say why", color = SliceBad, fontSize = 12.sp)
        } else {
            Text("no toolpath for this job", color = SliceDim, fontSize = 12.sp)
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("time", job.seconds?.let { fmtTime(it) } ?: "—", Modifier.weight(1f))
        Stat("grams", job.grams?.let { "%.2f".format(it) } ?: "—", Modifier.weight(1f))
        Stat("layers", job.layers?.toString() ?: "—", Modifier.weight(1f))
    }

    Spacer(Modifier.height(12.dp))
    SettingsCard(state, trays) { }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        scope.launch {
            state.busy = true; state.error = null
            try {
                withContext(Dispatchers.IO) { Slicer.reslice(baseUrl, job.id, state.params()) }
                val done = withContext(Dispatchers.IO) { pollUntilDoneBlocking(baseUrl, job.id) }
                state.open = done
                state.toolpath = withContext(Dispatchers.IO) {
                    runCatching { Slicer.toolpath(baseUrl, done.id) }.getOrNull()
                }
                state.jobs = withContext(Dispatchers.IO) { Slicer.jobs(baseUrl) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = e.message ?: e.toString()
            } finally { state.busy = false }
        }
    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.busy) "slicing…" else "Slice again with these settings")
    }
    Spacer(Modifier.height(10.dp))
    if (job.ready) {
        Text(job.cardPath ?: "", color = SliceOk, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val canPrint = !readOnly && state.error == null
        Button(onClick = { onPrint(job) }, enabled = !readOnly,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (readOnly) "Print — blocked by read-only" else "Print this")
        }
        Text(
            if (readOnly) "Read-only is on, and a print is a command. Settings → read-only off, then come back."
            else "The printer must be connected.",
            color = if (readOnly) SliceWarn else SliceDim, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    } else {
        if (!confirmSend) {
            Button(onClick = { confirmSend = true }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) {
                Text("Send to printer")
            }
            Text("Nothing goes to the card until you press this.", color = SliceDim,
                fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("Put ${job.name}.gcode.3mf on the printer's card?", color = SliceFg, fontSize = 14.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    confirmSend = false
                    scope.launch {
                        state.busy = true; state.error = null
                        try {
                            val done = withContext(Dispatchers.IO) { Slicer.sendToPrinter(baseUrl, job.id, state.tray) }
                            state.open = done
                            state.jobs = withContext(Dispatchers.IO) { Slicer.jobs(baseUrl) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            state.error = e.message ?: e.toString()
                        } finally { state.busy = false }
                    }
                }, enabled = !state.busy) { Text(if (state.busy) "sending…" else "Yes, send") }
                OutlinedButton(onClick = { confirmSend = false }) { Text("Cancel") }
            }
        }
    }
    state.error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = SliceBad, fontSize = 12.sp)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(SliceCard, RoundedCornerShape(9.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = SliceFg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = SliceDim, fontSize = 10.sp)
    }
}

/**
 * The 3D view. Paths are built once in model millimetres, then each layer is drawn through its
 * own matrix — a rotation maps a horizontal plane to a plane, so orbiting is only new matrices
 * and the 60 000 segments are not re-projected on the CPU every frame.
 */
@Composable
fun ToolpathView(tp: Toolpath, modifier: Modifier = Modifier) {
    var yaw by remember(tp) { mutableFloatStateOf(-0.7f) }
    var pitch by remember(tp) { mutableFloatStateOf(0.95f) }   // 0.02 = straight down
    var zoom by remember(tp) { mutableFloatStateOf(1f) }

    val paths = remember(tp) { buildPaths(tp) }
    val paint = remember { Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
    } }
    val matrix = remember { Matrix() }
    val gridPaint = remember { Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE
        strokeWidth = 1f; color = 0xFF1B212A.toInt() } }
    val bedPath = remember(tp) { buildBed(tp) }

    Canvas(modifier.background(Color(0xFF0D0F13), RoundedCornerShape(10.dp))
        .pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                yaw += drag.x * 0.013f
                // drag DOWN pulls the camera up over the model, as CAD and Bambu Studio do
                pitch = (pitch - drag.y * 0.010f).coerceIn(0.02f, 1.52f)
            }
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, _, gestureZoom, _ ->
                zoom = (zoom * gestureZoom).coerceIn(0.3f, 6f)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { yaw = -0.7f; pitch = 0.95f; zoom = 1f })
        }
    ) {
        val proj = Projection.fit(tp.box, size.width, size.height, zoom, yaw, pitch)
        val s = proj.scale

        try { drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // bed grid, at z = 0
            matrix.setValues(proj.matrix(0f))
            nc.save(); nc.concat(matrix)
            gridPaint.strokeWidth = 1f / maxOf(s, 0.001f) * 1.2f
            nc.drawPath(bedPath, gridPaint)
            nc.restore()

            // layers, lowest first: with the camera above, a lower layer is farther away
            val ordered = paths.sortedBy { it.first }
            for ((z, strokes) in ordered) {
                matrix.setValues(proj.matrix(z))
                nc.save(); nc.concat(matrix)
                paint.strokeWidth = maxOf(0.55f, 1.05f / maxOf(s, 0.001f))
                for ((name, path) in strokes) {
                    paint.color = featureColours[name] ?: DEFAULT_COLOUR
                    paint.alpha = 215
                    nc.drawPath(path, paint)
                }
                nc.restore()
            }
        }
        } catch (e: Throwable) {
            // a drawing bug is a bad picture, not a reason to lose the screen
            android.util.Log.w("bambuwatch", "toolpath view failed to draw: " + e, e)
        }
    }
}

private fun buildPaths(tp: Toolpath): List<Pair<Float, List<Pair<String, Path>>>> {
    val out = ArrayList<Pair<Float, List<Pair<String, Path>>>>(tp.layers.size)
    for (layer in tp.layers) {
        val byName = HashMap<String, Path>()
        for (i in 0 until layer.count) {
            val name = tp.names.getOrNull(layer.feat[i].toInt() and 0xFF) ?: "Custom"
            val p = byName.getOrPut(name) { Path() }
            val x0 = layer.seg[i * 4]; val y0 = layer.seg[i * 4 + 1]
            val x1 = layer.seg[i * 4 + 2]; val y1 = layer.seg[i * 4 + 3]
            if (p.isEmpty) p.moveTo(x0, y0) else p.moveTo(x0, y0)
            p.lineTo(x1, y1)
        }
        out.add(layer.z to byName.map { it.key to it.value })
    }
    return out
}

private fun buildBed(tp: Toolpath): Path {
    val p = Path()
    var g = 0f
    while (g <= tp.bedX) { p.moveTo(g, 0f); p.lineTo(g, tp.bedY); g += 20f }
    g = 0f
    while (g <= tp.bedY) { p.moveTo(0f, g); p.lineTo(tp.bedX, g); g += 20f }
    return p
}

private fun fmtTime(s: Int): String =
    if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${(s + 30) / 60} min"

/** wait for a job to stop working, for callers that then update the screen themselves */
private fun pollUntilDoneBlocking(baseUrl: String, jobId: String): Slicer.Job {
    var last = Slicer.job(baseUrl, jobId)
    var waited = 0
    while (last.busy && waited < 180) {
        Thread.sleep(700)
        last = Slicer.job(baseUrl, jobId)
        waited++
    }
    return last
}

/**
 * A filename worth sending. `uri.lastPathSegment` is a provider id — "1000045652" — for
 * anything picked from Downloads on Android, and the slicer used to guess `.stl` from that
 * and hand a zip to the STL reader. Ask the provider for the display name, fall back to the
 * MIME type, and let the server sniff the bytes whatever happens.
 */
private fun resolveName(ctx: android.content.Context, uri: android.net.Uri): String {
    val fromProvider = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val guess = (fromProvider ?: uri.lastPathSegment ?: "model").substringAfterLast('/').trim()
    if (guess.contains('.')) return guess
    val ext = when (ctx.contentResolver.getType(uri)) {
        "application/vnd.ms-package.3dmanufacturing-3mf" -> ".3mf"
        "model/stl", "application/sla" -> ".stl"
        "model/obj" -> ".obj"
        "application/step", "model/step" -> ".step"
        else -> ""
    }
    return guess + ext
}

/** poll while the slicer works, so the screen shows progress without a websocket */
private fun pollUntilDone(state: SliceState, baseUrl: String, jobId: String) {
    var last = Slicer.job(baseUrl, jobId)
    var waited = 0
    while (last.busy && waited < 180) {
        Thread.sleep(700)
        last = Slicer.job(baseUrl, jobId)
        waited++
    }
    state.jobs = Slicer.jobs(baseUrl)
    state.open = last
    state.toolpath = if (last.openable) runCatching { Slicer.toolpath(baseUrl, last.id) }.getOrNull() else null
}
