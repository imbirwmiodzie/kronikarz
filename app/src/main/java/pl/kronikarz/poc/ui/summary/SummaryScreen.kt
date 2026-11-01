package pl.kronikarz.poc.ui.summary

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.ui.common.BigButton
import pl.kronikarz.poc.ui.common.BigOutlinedButton
import pl.kronikarz.poc.ui.common.Thumb
import pl.kronikarz.poc.ui.common.plural
import pl.kronikarz.poc.ui.theme.AlbumColors
import java.io.File

/** Lista wszystkich osób + eksport. */
@Composable
fun SummaryScreen(app: KronikarzApp, sessionId: String, onOpenPerson: (String) -> Unit, onHome: () -> Unit) {
    val sessions by app.repository.sessions.collectAsState()
    val session = sessions[sessionId] ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lastZip by remember { mutableStateOf<File?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = lastZip ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.exporter.copyTo(zip, uri) }
                .onSuccess { Toast.makeText(context, "Zapisano plik", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, "Nie udało się zapisać: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun export(share: Boolean) {
        busy = true
        scope.launch {
            try {
                val zip = app.exporter.export(session, app.questions.questions)
                lastZip = zip
                if (share) {
                    try {
                        context.startActivity(app.exporter.shareIntent(zip))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "Brak aplikacji do udostępniania — użyj „Zapisz jako plik”", Toast.LENGTH_LONG).show()
                    }
                } else {
                    saveAs.launch(zip.name)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Eksport nie powiódł się: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val persons = session.activePersons
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Spacer(Modifier.height(20.dp))
                Text("Osoby na nagraniu", style = MaterialTheme.typography.headlineMedium)
                val r = session.recordings.size
                Text(
                    "${persons.size} ${plural(persons.size, "osoba", "osoby", "osób")} · $r ${plural(r, "nagranie", "nagrania", "nagrań")}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AlbumColors.InkSoft,
                )
                Spacer(Modifier.height(16.dp))
                if (busy) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    BigButton("Eksportuj (udostępnij)", onClick = { export(share = true) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    BigOutlinedButton("Zapisz eksport jako plik", onClick = { export(share = false) }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                BigOutlinedButton("Ekran główny", onClick = onHome, modifier = Modifier.fillMaxWidth())
            }
        }
        items(persons, key = { it.id }) { p ->
            val n = session.recordingsOf(p.id).size
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpenPerson(p.id) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Thumb(app.repository.file(sessionId, p.bestShot.cropPath), modifier = Modifier.fillMaxWidth(), maxPx = 300)
                Text(p.name?.takeIf { it.isNotBlank() } ?: "Osoba ${p.number}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (n == 0) "bez nagrań" else "$n ${plural(n, "nagranie", "nagrania", "nagrań")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n == 0) AlbumColors.Terracotta else AlbumColors.InkSoft,
                )
            }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(24.dp)) }
    }
}
