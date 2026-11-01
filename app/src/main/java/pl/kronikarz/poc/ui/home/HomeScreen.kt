package pl.kronikarz.poc.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.SessionStatus
import pl.kronikarz.poc.processing.ProcessingState
import pl.kronikarz.poc.ui.common.BigButton
import pl.kronikarz.poc.ui.common.BigOutlinedButton
import pl.kronikarz.poc.ui.common.Thumb
import pl.kronikarz.poc.ui.common.formatDuration
import pl.kronikarz.poc.ui.common.plural
import pl.kronikarz.poc.ui.theme.AlbumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    app: KronikarzApp,
    onVideoPicked: (Uri) -> Unit,
    onOpenSession: (Session) -> Unit,
    onOpenDebug: () -> Unit,
    onShowProcessing: () -> Unit,
) {
    val context = LocalContext.current
    val sessions by app.repository.sessions.collectAsState()
    val processing by app.processing.state.collectAsState()
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<Session?>(null) }

    // Systemowy wybór wideo — bez uprawnień do pamięci.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onVideoPicked)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onVideoPicked)
    }

    // Ukryty ekran debug: 5 szybkich stuknięć w tytuł.
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "Kto jest na nagraniu?",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.clickable(indication = null, interactionSource = null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 600) taps + 1 else 1
                    lastTap = now
                    if (taps >= 5) {
                        taps = 0
                        Toast.makeText(context, "Ustawienia debug", Toast.LENGTH_SHORT).show()
                        onOpenDebug()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Wybierz nagranie z telefonu. Aplikacja znajdzie osoby, a Ty opowiesz, kim są.",
                style = MaterialTheme.typography.bodyLarge,
                color = AlbumColors.InkSoft,
            )
        }
        if (processing is ProcessingState.Running) {
            item {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onShowProcessing),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AlbumColors.Terracotta),
                ) {
                    Text(
                        "Trwa przetwarzanie nagrania — stuknij, aby zobaczyć postęp",
                        style = MaterialTheme.typography.titleMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        item {
            BigButton(
                "Wybierz nagranie",
                enabled = processing !is ProcessingState.Running,
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            BigOutlinedButton(
                "Wybierz z plików",
                onClick = { filePicker.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Nagranie zostaje na telefonie. Aplikacja nie korzysta z internetu.",
                style = MaterialTheme.typography.bodySmall,
                color = AlbumColors.InkSoft,
            )
        }
        val list = sessions.values.sortedByDescending { it.createdAt }
        if (list.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Wcześniejsze nagrania", style = MaterialTheme.typography.titleLarge)
            }
        }
        items(list, key = { it.id }) { s ->
            SessionCard(app, s, onClick = { onOpenSession(s) }, onDelete = { toDelete = s })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Usunąć to nagranie z aplikacji?") },
            text = { Text("Znikną osoby i nagrane komentarze dla „${s.video.displayName}”. Oryginalne wideo zostanie w telefonie.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { app.repository.delete(s.id) }; toDelete = null }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun SessionCard(app: KronikarzApp, s: Session, onClick: () -> Unit, onDelete: () -> Unit) {
    val date = remember(s.createdAt) { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("pl")).format(Date(s.createdAt)) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = s.status == SessionStatus.READY, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AlbumColors.PaperDark),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val first = s.activePersons.firstOrNull()
            Thumb(first?.let { app.repository.file(s.id, it.bestShot.cropPath) }, modifier = Modifier.size(84.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(s.video.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text("$date · ${formatDuration(s.video.durationMs)}", style = MaterialTheme.typography.bodySmall, color = AlbumColors.InkSoft)
                val n = s.activePersons.size
                val r = s.recordings.size
                Text(
                    when (s.status) {
                        SessionStatus.READY -> "$n ${plural(n, "osoba", "osoby", "osób")} · $r ${plural(r, "nagranie", "nagrania", "nagrań")}"
                        SessionStatus.PROCESSING -> "Przetwarzanie…"
                        SessionStatus.FAILED -> s.errorMessage ?: "Błąd przetwarzania"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onDelete) { Text("Usuń", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
