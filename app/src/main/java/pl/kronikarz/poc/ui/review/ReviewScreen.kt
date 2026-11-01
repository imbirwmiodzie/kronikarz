package pl.kronikarz.poc.ui.review

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.PersonOps
import pl.kronikarz.poc.data.Recording
import pl.kronikarz.poc.ui.common.AppIcons
import pl.kronikarz.poc.ui.common.BigButton
import pl.kronikarz.poc.ui.common.BigOutlinedButton
import pl.kronikarz.poc.ui.common.PhotoPrint
import pl.kronikarz.poc.ui.common.Thumb
import pl.kronikarz.poc.ui.common.formatDuration
import pl.kronikarz.poc.ui.common.plural
import pl.kronikarz.poc.ui.theme.AlbumColors

@Composable
fun ReviewScreen(
    app: KronikarzApp,
    sessionId: String,
    personId: String?,
    onFinished: () -> Unit,
    onShowAll: () -> Unit,
) {
    val vm: ReviewViewModel = viewModel(key = "review-$sessionId", factory = ReviewViewModel.Factory(app, sessionId, personId))
    val ui by vm.ui.collectAsState()
    val rec by vm.recorder.collectAsState()
    val playing by vm.playingId.collectAsState()
    val undo by vm.undo.collectAsState()
    val finished by vm.finished.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(finished) {
        if (finished) {
            vm.consumeFinished(); onFinished()
        }
    }
    LaunchedEffect(undo) {
        val u = undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(u.text, actionLabel = "Cofnij", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undoLast() else vm.dismissUndo()
    }

    val u = ui
    Scaffold(
        containerColor = AlbumColors.Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (u != null) {
                Surface(color = AlbumColors.PaperDark, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BigOutlinedButton("Poprzednia", onClick = vm::previousPerson, enabled = u.index > 0, modifier = Modifier.weight(1f))
                        BigButton(
                            if (u.index + 1 >= u.persons.size) "Zakończ" else "Następna osoba",
                            onClick = vm::nextPerson,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (u == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Brak osób do przejrzenia", style = MaterialTheme.typography.headlineSmall)
            }
            return@Scaffold
        }
        PersonPage(app, vm, u, rec, playing, onShowAll, Modifier.padding(padding))
    }
}

@Composable
private fun PersonPage(
    app: KronikarzApp,
    vm: ReviewViewModel,
    u: ReviewUi,
    rec: RecorderUi,
    playing: String?,
    onShowAll: () -> Unit,
    modifier: Modifier,
) {
    val p = u.person
    var showMerge by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Recording?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(p.id) { scroll.scrollTo(0) }

    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Postęp
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Osoba ${u.index + 1} z ${u.persons.size}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onShowAll) { Text("Wszystkie osoby", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.height(12.dp))

        // Zdjęcie jak odbitka z albumu, lekko przechylone (na zmianę w lewo i w prawo)
        PhotoPrint(
            file = app.repository.file(u.session.id, p.bestShot.cropPath),
            tiltDeg = if (p.number % 2 == 0) 1.2f else -1.2f,
            modifier = Modifier.fillMaxWidth(0.86f),
            contentDescription = "Zdjęcie osoby ${p.number}",
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Pojawia się w ${p.occurrenceCount} ${plural(p.occurrenceCount, "ujęciu", "ujęciach", "ujęciach")}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (p.alternateShots.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                p.alternateShots.take(3).forEach { shot ->
                    Thumb(
                        app.repository.file(u.session.id, shot.cropPath),
                        modifier = Modifier.size(84.dp).clickable { vm.chooseShot(shot.faceId) }
                            .semantics { contentDescription = "Ustaw jako główne zdjęcie" },
                    )
                }
            }
            Text(
                "Stuknij mniejsze zdjęcie, jeśli jest lepsze",
                style = MaterialTheme.typography.bodySmall,
                color = AlbumColors.InkSoft,
            )
        }
        Spacer(Modifier.height(16.dp))

        NameField(p, onChange = { vm.setName(p.id, it) })
        Spacer(Modifier.height(20.dp))

        // Pytanie pomocnicze
        u.question?.let { q ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        if (q.index != null) "Pytanie ${q.index} z ${q.total}" else "Wszystkie pytania omówione",
                        style = MaterialTheme.typography.labelMedium,
                        color = AlbumColors.InkSoft,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(q.question.text, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        RecordButton(rec, onStart = vm::startRecording, onStop = vm::stopRecording)
        Spacer(Modifier.height(12.dp))
        BigOutlinedButton(
            if (u.question?.isClosing == true) "Wróć do pominiętych pytań" else "Następne pytanie",
            onClick = vm::nextQuestion,
            enabled = !rec.recording && (u.question?.isClosing != true || p.skippedQuestionIds.isNotEmpty()),
            modifier = Modifier.fillMaxWidth(),
        )

        if (u.recordings.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Nagrane odpowiedzi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            u.recordings.forEach { r ->
                RecordingRow(r, playing == r.id, onPlay = { vm.togglePlay(r) }, onDelete = { toDelete = r })
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = AlbumColors.InkSoft.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))
        BigOutlinedButton(
            "To ta sama osoba co…",
            onClick = { showMerge = true },
            enabled = u.persons.size > 1 && !rec.recording,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        BigOutlinedButton(
            "To nie jest osoba / błąd",
            onClick = { confirmHide = true },
            enabled = !rec.recording,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showMerge) {
        MergeDialog(app, u, onDismiss = { showMerge = false }, onMerge = { target ->
            showMerge = false
            vm.mergeCurrentInto(target.id)
        })
    }
    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("Ukryć to zdjęcie?") },
            text = { Text("Użyj tego, gdy to nie jest twarz albo zdjęcie jest błędne. Będzie można to cofnąć.") },
            confirmButton = { TextButton(onClick = { confirmHide = false; vm.hideCurrent() }) { Text("Ukryj") } },
            dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("Anuluj") } },
        )
    }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć to nagranie?") },
            text = { Text("„${r.questionText}” (${formatDuration(r.durationMs)})") },
            confirmButton = { TextButton(onClick = { vm.deleteRecording(r); toDelete = null }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun NameField(p: Person, onChange: (String) -> Unit) {
    // stan lokalny na osobę — zapis z opóźnieniem w ViewModelu
    var text by remember(p.id) { mutableStateOf(p.name.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text("Imię (opcjonalnie)", style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.titleLarge,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecordButton(rec: RecorderUi, onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStart() else denied = true
    }
    val click = {
        if (rec.recording) onStop()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onStart()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val level by animateFloatAsState(rec.level, tween(90), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
            if (rec.recording) {
                // pierścień rośnie z poziomem dźwięku
                Box(
                    Modifier.size(150.dp).scale(0.75f + 0.25f * level)
                        .clip(CircleShape).background(AlbumColors.Recording.copy(alpha = 0.22f))
                )
            }
            Box(
                Modifier.size(112.dp).scale(if (rec.recording) 1f else breathe)
                    .clip(CircleShape)
                    .background(if (rec.recording) AlbumColors.Recording else AlbumColors.Terracotta)
                    .clickable(onClick = click)
                    .semantics { contentDescription = if (rec.recording) "Zakończ nagrywanie" else "Nagraj odpowiedź" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (rec.recording) AppIcons.Stop else AppIcons.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        if (rec.recording) {
            Text("Nagrywam  ${formatDuration(rec.elapsedMs)}", style = MaterialTheme.typography.headlineSmall, color = AlbumColors.Recording)
            LevelBars(level)
            Text("Stuknij, aby zakończyć", style = MaterialTheme.typography.bodyMedium, color = AlbumColors.InkSoft)
        } else {
            Text("Stuknij i opowiedz", style = MaterialTheme.typography.titleLarge)
        }
        if (denied && !rec.recording) {
            Text(
                "Bez dostępu do mikrofonu nie da się nagrać odpowiedzi. Zezwól w ustawieniach telefonu.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlbumColors.Recording,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LevelBars(level: Float) {
    Row(
        Modifier.height(36.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val weights = listOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
        weights.forEach { w ->
            val h = (6 + 26 * (level * w)).coerceAtMost(28f)
            Box(Modifier.width(8.dp).height(h.dp).clip(RoundedCornerShape(4.dp)).background(AlbumColors.Recording))
        }
    }
}

@Composable
private fun RecordingRow(r: Recording, isPlaying: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AlbumColors.PaperDark),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPlay, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(if (isPlaying) AppIcons.Stop else Icons.Filled.PlayArrow, null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isPlaying) "Zatrzymaj" else "Odsłuchaj", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(r.questionText, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(formatDuration(r.durationMs), style = MaterialTheme.typography.bodySmall, color = AlbumColors.InkSoft)
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Usuń nagranie", modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun MergeDialog(app: KronikarzApp, u: ReviewUi, onDismiss: () -> Unit, onMerge: (Person) -> Unit) {
    var target by remember { mutableStateOf<Person?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AlbumColors.Paper) {
            Column(Modifier.padding(20.dp)) {
                Text("To ta sama osoba co…", style = MaterialTheme.typography.headlineSmall)
                Text("Wybierz zdjęcie tej samej osoby. Najbardziej podobne są na początku.", style = MaterialTheme.typography.bodyLarge, color = AlbumColors.InkSoft)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val candidates = PersonOps.mergeCandidates(u.session, u.person.id)
                    itemsIndexed(candidates, key = { _, c -> c.first.id }) { index, (other, sim) ->
                        // Ranking jest wiarygodny (na filmie testowym pierwsza karta to ta sama osoba w ~90%),
                        // ale sama wartość podobieństwa nie — dlatego wyróżniamy tylko pierwszego kandydata.
                        val likely = index == 0 && sim != null && sim >= SUGGEST_MIN_SIMILARITY
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { target = other }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Thumb(app.repository.file(u.session.id, other.bestShot.cropPath), selected = likely, modifier = Modifier.fillMaxWidth(), maxPx = 300)
                            if (likely) {
                                Text("Najbardziej podobna", style = MaterialTheme.typography.labelMedium, color = AlbumColors.Terracotta)
                            }
                            Text(
                                other.name?.takeIf { it.isNotBlank() } ?: "Osoba ${other.number}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${other.occurrenceCount} ${plural(other.occurrenceCount, "ujęcie", "ujęcia", "ujęć")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlbumColors.InkSoft,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BigOutlinedButton("Anuluj", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("Połączyć te osoby?") },
            text = { Text("Zdjęcia i nagrania tej osoby zostaną dołączone do: ${t.name?.takeIf { it.isNotBlank() } ?: "Osoba ${t.number}"}.") },
            confirmButton = { TextButton(onClick = { onMerge(t); target = null }) { Text("Połącz") } },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Anuluj") } },
        )
    }
}

/** Minimalne podobieństwo średnich embeddingów, żeby wyróżnić pierwszego kandydata do scalenia. */
private const val SUGGEST_MIN_SIMILARITY = 0.30f
