package pl.kronikarz.poc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Ciepła, spokojna paleta „rodzinnego albumu”, z wysokim kontrastem tekstu. */
object AlbumColors {
    val Paper = Color(0xFFF4ECDF)        // tło: kremowy papier
    val PaperDark = Color(0xFFE8DBC6)    // karty/sekcje
    val Ink = Color(0xFF2B1D14)          // tekst: prawie czarny brąz (13.9:1 na Paper)
    val InkSoft = Color(0xFF4F3B2C)      // tekst pomocniczy (9.0:1 na Paper, 7.7:1 na PaperDark)
    val Terracotta = Color(0xFF8F3F22)   // główne akcje (biały tekst: 7.2:1)
    val TerracottaDark = Color(0xFF6E3019)
    val Recording = Color(0xFF9E1F18)    // nagrywanie w toku (biały: 7.9:1)
    val Moss = Color(0xFF4E6B3A)         // potwierdzenia
    val PhotoBorder = Color(0xFFFFFFFF)
}

private val colors = lightColorScheme(
    primary = AlbumColors.Terracotta,
    onPrimary = Color.White,
    primaryContainer = AlbumColors.PaperDark,
    onPrimaryContainer = AlbumColors.Ink,
    secondary = AlbumColors.Moss,
    onSecondary = Color.White,
    background = AlbumColors.Paper,
    onBackground = AlbumColors.Ink,
    surface = AlbumColors.Paper,
    onSurface = AlbumColors.Ink,
    surfaceVariant = AlbumColors.PaperDark,
    onSurfaceVariant = AlbumColors.InkSoft,
    outline = AlbumColors.InkSoft,
    error = AlbumColors.Recording,
)

private val base = TextStyle(fontFamily = FontFamily.SansSerif, color = AlbumColors.Ink)

/** Duże czcionki — interfejs dla starszych osób. */
private val typography = Typography(
    displaySmall = base.copy(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = base.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = base.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp),
)

@Composable
fun KronikarzTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
