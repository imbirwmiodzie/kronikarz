package pl.kronikarz.poc.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.analysis.FaceClusterer
import pl.kronikarz.poc.data.FaceRecord
import pl.kronikarz.poc.data.PersonStatus
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.ui.common.BigOutlinedButton
import pl.kronikarz.poc.ui.common.Thumb
import pl.kronikarz.poc.ui.common.formatDuration
import pl.kronikarz.poc.ui.theme.AlbumColors
import kotlin.math.roundToInt

/** Ukryty ekran do strojenia parametrów i podglądu grupowania. */
@Composable
fun DebugScreen(app: KronikarzApp, onBack: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    val sessions by app.repository.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var faces by remember { mutableStateOf<Map<Int, FaceRecord>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    val session = selected?.let { sessions[it] }

    LaunchedEffect(selected) {
        faces = selected?.let { app.repository.loadFaceRecords(it) }?.associateBy { it.id } ?: emptyMap()
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug: strojenie", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 16.dp))
                TextButton(onClick = onBack) { Text("Zamknij") }
            }
            Text(
                "Ustawienia dotyczą kolejnych przetworzeń. Próg i min. wystąpień można też zastosować do istniejącej sesji („Przelicz grupy”).",
                style = MaterialTheme.typography.bodySmall,
                color = AlbumColors.InkSoft,
            )
        }
        item {
            SliderRow("Próbkowanie co (ms)", settings.sampleIntervalMs.toFloat(), 100f..2000f, steps = 18,
                format = { "${it.roundToInt()} ms (${"%.1f".format(1000f / it)} kl./s)" }) { v ->
                app.settings.update { it.copy(sampleIntervalMs = (v / 100).roundToInt() * 100) }
            }
            SliderRow("Min. wysokość twarzy (px)", settings.minFaceHeightPx.toFloat(), 16f..160f, steps = 17,
                format = { "${it.roundToInt()} px" }) { v ->
                app.settings.update { it.copy(minFaceHeightPx = (v / 8).roundToInt() * 8) }
            }
            SliderRow("Próg podobieństwa", settings.similarityThreshold, 0.15f..0.80f, steps = 64,
                format = { "%.2f".format(it) }) { v ->
                app.settings.update { it.copy(similarityThreshold = (v * 100).roundToInt() / 100f) }
            }
            SliderRow("Min. liczba wystąpień", settings.minOccurrences.toFloat(), 1f..10f, steps = 8,
                format = { "${it.roundToInt()}" }) { v ->
                app.settings.update { it.copy(minOccurrences = v.roundToInt()) }
            }
            SliderRow("Szukanie ostrzejszej klatki (±ms, 0 = wył.)", settings.refineWindowMs.toFloat(), 0f..400f, steps = 9,
                format = { if (it < 1f) "wyłączone" else "±${it.roundToInt()} ms" }) { v ->
                app.settings.update { it.copy(refineWindowMs = (v / 40).roundToInt() * 40) }
            }
            SliderRow("Maks. szerokość klatki (px)", settings.maxFrameWidth.toFloat(), 480f..1920f, steps = 8,
                format = { "${it.roundToInt()} px" }) { v ->
                app.settings.update { it.copy(maxFrameWidth = (v / 160).roundToInt() * 160) }
            }
            SwitchRow("Dokładne klatki (OPTION_CLOSEST, wolniej)", settings.exactFrames) { v -> app.settings.update { it.copy(exactFrames = v) } }
            SwitchRow("Dekoder sekwencyjny MediaCodec (eksperym., szybszy przy długim GOP)", settings.sequentialDecoder) { v -> app.settings.update { it.copy(sequentialDecoder = v) } }
            SwitchRow("Zachowaj dane do strojenia (wszystkie twarze)", settings.keepTuningData) { v -> app.settings.update { it.copy(keepTuningData = v) } }
            TextButton(onClick = { app.settings.reset() }) { Text("Przywróć domyślne") }
        }

        item {
            Text("Sesje", style = MaterialTheme.typography.titleLarge)
            sessions.values.sortedByDescending { it.createdAt }.forEach { s ->
                Text(
                    (if (s.id == selected) "▶ " else "") + s.video.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { selected = s.id }.padding(vertical = 8.dp),
                )
            }
        }

        if (session != null) {
            item { StatsBlock(session) }
            item {
                BigOutlinedButton(
                    "Przelicz grupy z bieżącym progiem (${"%.2f".format(settings.similarityThreshold)}, min ${settings.minOccurrences})",
                    enabled = session.recordings.isEmpty() && faces.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            message = app.processing.regroup(session.id, settings).fold(
                                onSuccess = { "Przeliczono: ${it.activePersons.size} osób" },
                                onFailure = { "Błąd: ${it.message}" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session.recordings.isNotEmpty()) {
                    Text("Sesja ma nagrania — przeliczenie zablokowane.", style = MaterialTheme.typography.bodySmall)
                }
                if (faces.isEmpty()) {
                    Text("Brak faces.json (dane do strojenia były wyłączone).", style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            val groups = groupsOf(session, faces)
            item { Text("Grupy (${groups.size})", style = MaterialTheme.typography.titleLarge) }
            items(groups, key = { it.key }) { g -> GroupRow(app, session.id, g, faces) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

private data class DebugGroup(
    val key: String,
    val title: String,
    val faceIds: List<Int>,
    /** Średnie podobieństwo wewnątrz grupy i do najbliższej innej grupy. */
    val intra: Float?,
    val nearest: Pair<String, Float>?,
)

private fun groupsOf(s: Session, faces: Map<Int, FaceRecord>): List<DebugGroup> {
    val raw = s.persons.map { p ->
        val st = when (p.status) {
            PersonStatus.ACTIVE -> "aktywna"
            PersonStatus.HIDDEN -> "ukryta"
            PersonStatus.MERGED -> "scalona → ${s.persons.firstOrNull { it.id == p.mergedInto }?.number ?: "?"}"
        }
        Triple("p${p.id}", "Osoba ${p.number}${p.name?.let { " ($it)" } ?: ""} · $st · ${p.occurrenceCount} tw.", p.faceIds)
    } + s.rejectedGroups.mapIndexed { i, g -> Triple("r$i", "Odrzucona (szum) #${i + 1} · ${g.faceIds.size} tw.", g.faceIds) }

    val emb = raw.map { (_, _, ids) -> ids.mapNotNull { faces[it]?.embedding } }
    return raw.mapIndexed { i, (key, title, ids) ->
        val e = emb[i]
        val intra = if (e.size >= 2) FaceClusterer.averageSimilarity(e, e) else null
        val nearest = if (e.isEmpty()) null else raw.indices.filter { it != i && emb[it].isNotEmpty() }
            .map { j -> raw[j].second.substringBefore(" ·") to FaceClusterer.averageSimilarity(e, emb[j]) }
            .maxByOrNull { it.second }
        DebugGroup(key, title, ids, intra, nearest)
    }
}

@Composable
private fun GroupRow(app: KronikarzApp, sessionId: String, g: DebugGroup, faces: Map<Int, FaceRecord>) {
    Column(Modifier.fillMaxWidth().background(AlbumColors.PaperDark, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(g.title, style = MaterialTheme.typography.titleMedium)
        val meta = buildString {
            g.intra?.let { append("wewn. %.2f".format(it)) }
            g.nearest?.let { append("   najbliżej: ${it.first} %.2f".format(it.second)) }
        }
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        val ordered = g.faceIds.mapNotNull { faces[it] }.sortedByDescending { it.quality.total }
        if (ordered.isEmpty()) {
            Text("(brak wycinków)", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ordered, key = { it.id }) { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(app.repository.file(sessionId, f.cropPath), modifier = Modifier.size(72.dp), maxPx = 96)
                        Text(
                            "%.2f %s".format(f.quality.total, formatDuration(f.timestampMs)),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsBlock(s: Session) {
    val st = s.stats ?: return
    val text = buildString {
        appendLine("Wideo: ${formatDuration(st.videoDurationMs)}, ${s.video.width}×${s.video.height}")
        appendLine("Urządzenie: ${st.device}")
        appendLine("Czas: ${formatDuration(st.wallClockMs)}  →  ${"%.1f".format(st.msPerVideoMinute / 1000f)} s na minutę wideo")
        appendLine("  dekodowanie ${st.decodeMs / 1000}s, detekcja ${st.detectMs / 1000}s, embedding ${st.embedMs / 1000}s, reszta ${st.otherMs / 1000}s, grupowanie ${st.clusterMs}ms")
        appendLine("Klatki: ${st.framesSampled} (nieudane ${st.framesFailed}, powtórzone ${st.framesDuplicate}), twarze wykryte: ${st.facesDetected}, przyjęte: ${st.facesAccepted}")
        appendLine("Doszukiwanie ostrzejszych ujęć: ${st.refineImproved} z ${st.refineShots} poprawionych, ${st.refineMs / 1000}s")
        appendLine("Odrzucone: za małe ${st.rejectedTooSmall}, ucięte ${st.rejectedAtEdge}, bez punktów ${st.rejectedNoLandmarks}")
        appendLine("Dekoder: ${if (s.settings.sequentialDecoder) "MediaCodec (sekwencyjny)" else "MediaMetadataRetriever"}${if (s.settings.exactFrames) "" else ", klatki kluczowe"}")
        appendLine("Ustawienia sesji: co ${s.settings.sampleIntervalMs} ms, min ${s.settings.minFaceHeightPx} px, próg ${s.settings.similarityThreshold}, min wyst. ${s.settings.minOccurrences}")
        append("Osoby: ${s.activePersons.size} aktywne, ${s.persons.count { it.status == PersonStatus.MERGED }} scalone, ${s.persons.count { it.status == PersonStatus.HIDDEN }} ukryte")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${format(v)}", style = MaterialTheme.typography.bodyLarge)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
