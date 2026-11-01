package pl.kronikarz.poc.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.kronikarz.poc.analysis.ImageOps
import java.io.File

/** Minimalny loader miniatur z plików lokalnych (bez dodatkowych bibliotek). */
object ImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun load(file: File, maxPx: Int): ImageBitmap? {
        val key = "${file.absolutePath}@$maxPx"
        cache.get(key)?.let { return it }
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        bmp = enlargeSmall(bmp, maxPx)
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * Wycinki z VHS mają często 60–120 px, a na ekranie zajmują ~1000 px. System powiększa je dwuliniowo,
 * co wygląda na „rozmyte”. Tu: lekkie wyostrzenie w oryginalnej rozdzielczości + powiększenie
 * dwusześcienne (maks. 4×). Szczegółów nie przybywa, ale krawędzie są czystsze. Pliki zostają bez zmian.
 */
private fun enlargeSmall(src: Bitmap, maxPx: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    val factor = minOf(4f, maxPx.toFloat() / longest)
    if (factor < 1.5f || longest > 512) return src
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val sharp = ImageOps.unsharp(px, w, h, amount = 0.6f)
    val nw = (w * factor).toInt()
    val nh = (h * factor).toInt()
    val big = ImageOps.resizeBicubic(sharp, w, h, nw, nh)
    return Bitmap.createBitmap(big, nw, nh, Bitmap.Config.ARGB_8888)
}

@Composable
fun rememberImage(file: File?, maxPx: Int = 512): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, file, maxPx) {
        value = file?.let { withContext(Dispatchers.IO) { ImageCache.load(it, maxPx) } }
    }
    return image
}
