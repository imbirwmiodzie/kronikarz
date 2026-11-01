package pl.kronikarz.poc.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.kronikarz.poc.ui.theme.AlbumColors
import java.io.File

/** Zdjęcie jak odbitka z albumu: biała ramka, lekki cień, opcjonalnie lekko przechylone. */
@Composable
fun PhotoPrint(
    file: File?,
    modifier: Modifier = Modifier,
    border: Dp = 14.dp,
    tiltDeg: Float = 0f,
    maxPx: Int = 800,
    contentDescription: String? = null,
) {
    val img = rememberImage(file, maxPx)
    Box(
        modifier
            .rotate(tiltDeg)
            .shadow(6.dp, RoundedCornerShape(3.dp))
            .background(AlbumColors.PhotoBorder, RoundedCornerShape(3.dp))
            .padding(border)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(AlbumColors.PaperDark)
        ) {
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    // wycinki z VHS są małe — gładkie skalowanie zamiast „pikselozy”
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun Thumb(file: File?, selected: Boolean = false, modifier: Modifier = Modifier, maxPx: Int = 200) {
    val img = rememberImage(file, maxPx)
    Box(
        modifier
            .aspectRatio(1f)
            .background(AlbumColors.PhotoBorder)
            .border(if (selected) 4.dp else 0.dp, if (selected) AlbumColors.Terracotta else Color.Transparent)
            .padding(5.dp)
            .background(AlbumColors.PaperDark),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(img, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Duży przycisk główny (min. 64 dp wysokości). */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Duży przycisk drugorzędny (obrys). */
@Composable
fun BigOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, AlbumColors.InkSoft),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlbumColors.Ink),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Polska odmiana: 1 ujęcie, 2–4 ujęcia, 5+ ujęć (z wyjątkiem 12–14). */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        n == 1 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}
