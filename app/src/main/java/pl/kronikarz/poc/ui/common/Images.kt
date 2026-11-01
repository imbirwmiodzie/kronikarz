package pl.kronikarz.poc.ui.common

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

@Composable
fun rememberImage(file: File?, maxPx: Int = 512): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, file, maxPx) {
        value = file?.let { withContext(Dispatchers.IO) { ImageCache.load(it, maxPx) } }
    }
    return image
}
