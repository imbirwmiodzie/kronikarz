package pl.kronikarz.poc.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import pl.kronikarz.poc.data.VideoInfo

/**
 * Sekwencyjne dekodowanie MediaCodec: każda klatka filmu jest dekodowana raz, a na bitmapę
 * konwertujemy tylko klatki próbkowane. Przy nagraniach z długim GOP (zgrania z kart
 * przechwytujących, klatka kluczowa co kilka sekund) jest to wielokrotnie szybsze niż
 * MediaMetadataRetriever, który dla każdej próbki dekoduje od poprzedniej klatki kluczowej.
 *
 * Wymaga rosnących czasów w [frameAt] (tak działa VideoProcessor). EKSPERYMENTALNE —
 * włączane w ekranie debug; przy błędzie [FallbackFrameSource] przełącza się na retriever.
 */
class CodecFrameSource(
    context: Context,
    uri: Uri,
    override val info: VideoInfo,
    private val maxWidth: Int,
) : FrameSource {

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    private var inputDone = false
    private var outputDone = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("Brak ścieżki wideo")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        val targetUs = timeMs * 1000
        var idle = 0
        while (!outputDone) {
            feedInput()
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (index < 0) {
                // brak klatki / zmiana formatu — próbujemy dalej, ale nie w nieskończoność
                if (++idle > MAX_IDLE_POLLS) error("Dekoder nie zwraca klatek")
                continue
            }
            idle = 0
            val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = bufferInfo.presentationTimeUs
            var result: Bitmap? = null
            if (bufferInfo.size > 0 && pts >= targetUs) {
                val image = codec.getOutputImage(index)
                if (image == null) {
                    codec.releaseOutputBuffer(index, false)
                    error("Dekoder nie udostępnia obrazu YUV") // -> FallbackFrameSource
                }
                result = image.use { toBitmap(it) }
            }
            codec.releaseOutputBuffer(index, false)
            if (eos) outputDone = true
            if (result != null) return result
        }
        return null
    }

    private fun feedInput() {
        if (inputDone) return
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        val buf = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buf, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** YUV_420_888 -> ARGB_8888 (BT.601, zakres ograniczony), z uwzględnieniem kroków płaszczyzn. */
    private fun toBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        val uvRow = uP.rowStride
        val uvPix = uP.pixelStride
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = y + crop.top
            val yBase = sy * yRow
            val uvBase = (sy / 2) * uvRow
            for (x in 0 until w) {
                val sx = x + crop.left
                val yy = (yBuf.get(yBase + sx * yPix).toInt() and 0xFF) - 16
                val uvOff = uvBase + (sx / 2) * uvPix
                val u = (uBuf.get(uvOff).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvOff).toInt() and 0xFF) - 128
                val c = 1192 * yy.coerceAtLeast(0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        var bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            bmp.recycle()
            bmp = rotated
        }
        val scale = minOf(1f, maxWidth.toFloat() / bmp.width)
        val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val th = (bmp.height * scale).toInt().coerceAtLeast(1)
        if (tw != bmp.width || th != bmp.height) {
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    private companion object {
        /** 500 × 10 ms bez żadnej klatki = dekoder się zaciął. */
        const val MAX_IDLE_POLLS = 500
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

/**
 * Źródło z zapasem: najpierw [primary], a po pierwszym błędzie — na stałe [fallback()].
 */
class FallbackFrameSource(
    private var primary: FrameSource?,
    private val fallbackFactory: () -> FrameSource,
) : FrameSource {
    private var fallback: FrameSource? = null
    override val info: VideoInfo = primary?.info ?: fallbackFactory().also { fallback = it }.info

    /** true, gdy w trakcie przełączono się na zapasowe źródło. */
    var usedFallback = false
        private set

    override fun frameAt(timeMs: Long): Bitmap? {
        primary?.let { p ->
            try {
                return p.frameAt(timeMs)
            } catch (e: Exception) {
                Log.w("FrameSource", "Dekoder sekwencyjny zawiódł przy $timeMs ms, przełączam na MediaMetadataRetriever", e)
                runCatching { p.close() }
                primary = null
                usedFallback = true
            }
        }
        val f = fallback ?: fallbackFactory().also { fallback = it }
        return f.frameAt(timeMs)
    }

    override fun close() {
        primary?.close()
        fallback?.close()
    }
}
