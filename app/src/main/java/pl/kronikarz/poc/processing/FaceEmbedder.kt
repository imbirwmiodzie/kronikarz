package pl.kronikarz.poc.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import pl.kronikarz.poc.analysis.FaceAlignment
import pl.kronikarz.poc.analysis.VectorMath
import java.nio.FloatBuffer

/**
 * Embedding twarzy modelem SFace (MobileFaceNet wytrenowany funkcją straty SFace, OpenCV Zoo,
 * licencja Apache 2.0). Wejście: wyrównana twarz 112×112 RGB, wartości 0..255, układ NCHW.
 * Wyjście: wektor 128 liczb, normalizowany L2.
 */
class FaceEmbedder(context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val aligned = Bitmap.createBitmap(FaceAlignment.SIZE, FaceAlignment.SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(aligned)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pixels = IntArray(FaceAlignment.SIZE * FaceAlignment.SIZE)
    private val input = FloatBuffer.allocate(3 * FaceAlignment.SIZE * FaceAlignment.SIZE)

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        session = env.createSession(bytes, opts)
        inputName = if ("data" in session.inputNames) "data" else session.inputNames.first()
    }

    /**
     * Wyrównuje twarz macierzą [transform] (klatka -> szablon 112×112) i liczy embedding.
     * Zwraca też wyrównany wycinek (do podglądu w debug — nie jest zapisywany).
     */
    fun embed(frame: Bitmap, transform: FloatArray): FloatArray {
        val m = Matrix().apply {
            setValues(floatArrayOf(transform[0], transform[1], transform[2], transform[3], transform[4], transform[5], 0f, 0f, 1f))
        }
        aligned.eraseColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(frame, m, paint)
        aligned.getPixels(pixels, 0, FaceAlignment.SIZE, 0, 0, FaceAlignment.SIZE, FaceAlignment.SIZE)

        val plane = FaceAlignment.SIZE * FaceAlignment.SIZE
        input.clear()
        val arr = input.array()
        for (i in 0 until plane) {
            val c = pixels[i]
            arr[i] = ((c shr 16) and 0xFF).toFloat()            // R
            arr[plane + i] = ((c shr 8) and 0xFF).toFloat()     // G
            arr[2 * plane + i] = (c and 0xFF).toFloat()         // B
        }
        val shape = longArrayOf(1, 3, FaceAlignment.SIZE.toLong(), FaceAlignment.SIZE.toLong())
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<FloatArray>)[0]
                return VectorMath.normalize(out)
            }
        }
    }

    override fun close() {
        session.close()
        aligned.recycle()
    }

    companion object {
        const val MODEL_ASSET = "models/sface.onnx"
    }
}
