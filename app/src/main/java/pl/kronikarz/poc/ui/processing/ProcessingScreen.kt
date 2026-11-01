package pl.kronikarz.poc.ui.processing

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.processing.ProcessingProgress
import pl.kronikarz.poc.processing.ProcessingState
import pl.kronikarz.poc.ui.common.BigButton
import pl.kronikarz.poc.ui.common.BigOutlinedButton
import pl.kronikarz.poc.ui.common.formatDuration
import pl.kronikarz.poc.ui.common.plural
import pl.kronikarz.poc.ui.theme.AlbumColors

@Composable
fun ProcessingScreen(app: KronikarzApp, onDone: (sessionId: String) -> Unit, onBack: () -> Unit) {
    val state by app.processing.state.collectAsState()
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }

    // Ekran nie gaśnie w trakcie przetwarzania (system mógłby wtedy uśpić aplikację).
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(state) {
        (state as? ProcessingState.Done)?.let {
            app.processing.acknowledge()
            onDone(it.sessionId)
        }
    }

    BackHandler(enabled = state is ProcessingState.Running) { confirmCancel = true }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is ProcessingState.Running -> Running(s, onCancel = { confirmCancel = true })
            is ProcessingState.Failed -> {
                Text("Nie udało się przetworzyć nagrania", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(s.message, style = MaterialTheme.typography.bodyLarge, color = AlbumColors.InkSoft)
                Spacer(Modifier.height(24.dp))
                BigButton("Wróć", onClick = { app.processing.acknowledge(); onBack() }, modifier = Modifier.fillMaxWidth())
            }
            ProcessingState.Cancelled -> {
                Text("Przerwano przetwarzanie", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                BigButton("Wróć", onClick = { app.processing.acknowledge(); onBack() }, modifier = Modifier.fillMaxWidth())
            }
            else -> Text("…", style = MaterialTheme.typography.headlineSmall)
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Przerwać przetwarzanie?") },
            text = { Text("Dotychczasowe wyniki zostaną usunięte.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; app.processing.cancel() }) { Text("Przerwij") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Kontynuuj") } },
        )
    }
}

@Composable
private fun Running(s: ProcessingState.Running, onCancel: () -> Unit) {
    val p = s.progress
    Text("Szukam osób na nagraniu", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(s.videoName, style = MaterialTheme.typography.bodyLarge, color = AlbumColors.InkSoft)
    Spacer(Modifier.height(32.dp))
    if (p == null) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)),
            trackColor = AlbumColors.PaperDark,
        )
        Spacer(Modifier.height(16.dp))
        Text("Otwieram nagranie…", style = MaterialTheme.typography.bodyLarge)
    } else {
        LinearProgressIndicator(
            progress = { p.fraction },
            modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)),
            trackColor = AlbumColors.PaperDark,
        )
        Spacer(Modifier.height(16.dp))
        Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when (p.phase) {
                ProcessingProgress.Phase.FRAMES -> "Przejrzano ${formatDuration(p.processedMs)} z ${formatDuration(p.durationMs)}"
                ProcessingProgress.Phase.GROUPING -> "Grupuję twarze w osoby…"
                ProcessingProgress.Phase.SAVING -> "Zapisuję…"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Znalezione twarze: ${p.facesFound}",
            style = MaterialTheme.typography.bodyLarge,
            color = AlbumColors.InkSoft,
        )
        p.etaMs?.let {
            val min = (it / 60_000).toInt()
            Text(
                if (min < 1) "Zostało mniej niż minuta" else "Zostało ok. $min ${plural(min, "minuta", "minuty", "minut")}",
                style = MaterialTheme.typography.bodyLarge,
                color = AlbumColors.InkSoft,
            )
        }
    }
    if (p != null && p.durationMs > LONG_VIDEO_MS) {
        Spacer(Modifier.height(16.dp))
        Text(
            "To długie nagranie (${formatDuration(p.durationMs)}). Wersja próbna jest przygotowana na nagrania do ok. 10 minut — przetwarzanie może potrwać.",
            style = MaterialTheme.typography.bodyMedium,
            color = AlbumColors.TerracottaDark,
        )
    }
    Spacer(Modifier.height(40.dp))
    Text(
        "Nie wyłączaj ekranu i nie przełączaj aplikacji.",
        style = MaterialTheme.typography.bodyMedium,
        color = AlbumColors.InkSoft,
    )
    Spacer(Modifier.height(16.dp))
    BigOutlinedButton("Anuluj", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

private const val LONG_VIDEO_MS = 10 * 60_000L
