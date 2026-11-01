package pl.kronikarz.poc.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import pl.kronikarz.poc.data.VideoInfo

/**
 * Źródło klatek wideo. POC używa [MediaMetadataRetriever]; gdyby było za wolne
 * (np. nagrania z kart przechwytujących z bardzo długim GOP), można tu podstawić
 * implementację na MediaCodec dekodującą sekwencyjnie.
 */
interface FrameSource : AutoCloseable {
    val info: VideoInfo

    /** Klatka najbliższa [timeMs] jako ARGB_8888 albo null, gdy nie da się jej zdekodować. */
    fun frameAt(timeMs: Long): Bitmap?
}

class RetrieverFrameSource(
    context: Context,
    uri: Uri,
    private val exact: Boolean,
    private val maxWidth: Int,
) : FrameSource {

    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    override val info: VideoInfo

    init {
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (rw, rh) = if (rotation == 90 || rotation == 270) h to w else w to h
        val fps = frameRate(duration)
        info = VideoInfo(uri.toString(), displayName(context, uri), duration, rw, rh, fps)
    }

    private fun frameRate(durationMs: Long): Float {
        val fromCapture = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        if (fromCapture != null && fromCapture in 5f..240f) return fromCapture
        if (Build.VERSION.SDK_INT >= 28 && durationMs > 0) {
            val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            if (count != null && count > 0) {
                val fps = count * 1000f / durationMs
                if (fps in 5f..240f) return fps
            }
        }
        return 0f
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val us = timeMs * 1000
        val raw = runCatching {
            if (Build.VERSION.SDK_INT >= 27 && info.width > maxWidth && info.width > 0) {
                val dstH = (info.height.toLong() * maxWidth / info.width).toInt()
                retriever.getScaledFrameAtTime(us, option, maxWidth, dstH)
            } else {
                retriever.getFrameAtTime(us, option)
            }
        }.getOrNull() ?: return null

        // ML Kit i nasze obliczenia pikseli potrzebują ARGB_8888 (dekoder czasem daje RGB_565).
        val argb = if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        if (argb.width <= maxWidth) return argb
        // API 26: brak getScaledFrameAtTime — skalujemy sami.
        val scaled = Bitmap.createScaledBitmap(argb, maxWidth, argb.height * maxWidth / argb.width, true)
        if (scaled !== argb) argb.recycle()
        return scaled
    }

    override fun close() {
        runCatching { retriever.release() }
    }

    companion object {
        fun displayName(context: Context, uri: Uri): String =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "nagranie"
    }
}
