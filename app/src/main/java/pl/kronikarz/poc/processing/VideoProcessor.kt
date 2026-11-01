package pl.kronikarz.poc.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import pl.kronikarz.poc.analysis.FaceAlignment
import pl.kronikarz.poc.analysis.FaceObservation
import pl.kronikarz.poc.analysis.GrayImage
import pl.kronikarz.poc.analysis.QualityScorer
import pl.kronikarz.poc.data.FaceRecord
import pl.kronikarz.poc.data.PersonOps
import pl.kronikarz.poc.data.ProcessingSettings
import pl.kronikarz.poc.data.ProcessingStats
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.SessionRepository
import pl.kronikarz.poc.data.SessionStatus
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ProcessingProgress(
    val processedMs: Long,
    val durationMs: Long,
    val framesProcessed: Int,
    val facesFound: Int,
    val elapsedMs: Long,
    val phase: Phase,
) {
    enum class Phase { FRAMES, GROUPING, SAVING }

    val fraction: Float get() = if (durationMs <= 0) 0f else (processedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Szacowany pozostały czas na podstawie dotychczasowego tempa. */
    val etaMs: Long? get() = if (fraction < 0.03f) null else (elapsedMs / fraction - elapsedMs).toLong()
}

/**
 * Cały potok: klatki -> twarze -> filtry -> embedding + ocena jakości -> wycinki -> grupowanie -> osoby.
 */
class VideoProcessor(
    private val context: Context,
    private val repository: SessionRepository,
) {
    private val scorer = QualityScorer()

    suspend fun process(
        session: Session,
        source: FrameSource,
        onProgress: (ProcessingProgress) -> Unit,
    ): Session {
        val settings = session.settings
        val info = source.info
        val sessionDir = repository.dir(session.id)
        val facesDir = File(sessionDir, FACES_DIR).apply { mkdirs() }
        val start = SystemClock.elapsedRealtime()

        var decodeMs = 0L
        var detectMs = 0L
        var embedMs = 0L
        var otherMs = 0L
        var tooSmall = 0
        var atEdge = 0
        var noLandmarks = 0
        var detected = 0
        var frames = 0
        val records = mutableListOf<FaceRecord>()

        // Względny minimalny rozmiar dla ML Kit (szerokość głowy / szerokość obrazu) — trochę
        // poniżej progu w pikselach, żeby to nasz filtr decydował, a detektor nie gubił twarzy.
        val frameW = min(info.width.takeIf { it > 0 } ?: settings.maxFrameWidth, settings.maxFrameWidth)
        val relMin = (settings.minFaceHeightPx * 0.8f / frameW).coerceIn(0.02f, 0.5f)

        FaceDetectorEngine(relMin).use { detector ->
            FaceEmbedder(context).use { embedder ->
                var t = 0L
                var lastHash = 0L
                while (t < info.durationMs) {
                    coroutineContext.ensureActive()
                    var tick = SystemClock.elapsedRealtime()
                    val frame = source.frameAt(t)
                    decodeMs += SystemClock.elapsedRealtime() - tick

                    if (frame != null) {
                        val hash = frameHash(frame)
                        // W trybie klatek kluczowych dekoder potrafi zwrócić tę samą klatkę kilka razy.
                        if (hash != lastHash) {
                            lastHash = hash
                            // numer przetworzonej próbki: kolejne próbki = kolejne liczby (śledzenie w grupowaniu)
                            val frameIndex = frames
                            frames++
                            tick = SystemClock.elapsedRealtime()
                            val faces = detector.detect(frame)
                            detectMs += SystemClock.elapsedRealtime() - tick
                            detected += faces.size

                            for (f in faces) {
                                if (f.box.height() < settings.minFaceHeightPx) {
                                    tooSmall++; continue
                                }
                                if (visibleFraction(f.box, frame.width, frame.height) < 0.92f) {
                                    atEdge++; continue
                                }
                                val pairs = FaceAlignment.pairs(f.leftEye, f.rightEye, f.nose, f.mouthLeft, f.mouthRight)
                                if (pairs == null) {
                                    noLandmarks++; continue
                                }
                                tick = SystemClock.elapsedRealtime()
                                val transform = FaceAlignment.similarityTransform(pairs.first, pairs.second)
                                val embedding = embedder.embed(frame, transform)
                                embedMs += SystemClock.elapsedRealtime() - tick

                                tick = SystemClock.elapsedRealtime()
                                val id = records.size
                                val quality = scorer.score(
                                    FaceObservation(
                                        face = grayRegion(frame, f.box),
                                        faceHeightPx = f.box.height().toFloat(),
                                        yawDeg = f.yaw, pitchDeg = f.pitch, rollDeg = f.roll,
                                        leftEyeOpen = f.leftEyeOpen, rightEyeOpen = f.rightEyeOpen,
                                        smiling = f.smiling,
                                    )
                                )
                                val cropName = "f_$id.jpg"
                                saveCrop(frame, f.box, File(facesDir, cropName))
                                records += FaceRecord(
                                    id = id, frameIndex = frameIndex, timestampMs = t,
                                    left = f.box.left, top = f.box.top, right = f.box.right, bottom = f.box.bottom,
                                    yaw = f.yaw, pitch = f.pitch, roll = f.roll,
                                    quality = quality,
                                    cropPath = "$FACES_DIR/$cropName",
                                    embedding = embedding,
                                )
                                otherMs += SystemClock.elapsedRealtime() - tick
                            }
                        }
                        frame.recycle()
                    }
                    t += settings.sampleIntervalMs
                    onProgress(
                        ProcessingProgress(
                            processedMs = min(t, info.durationMs), durationMs = info.durationMs,
                            framesProcessed = frames, facesFound = records.size,
                            elapsedMs = SystemClock.elapsedRealtime() - start,
                            phase = ProcessingProgress.Phase.FRAMES,
                        )
                    )
                }
            }
        }

        onProgress(
            ProcessingProgress(info.durationMs, info.durationMs, frames, records.size,
                SystemClock.elapsedRealtime() - start, ProcessingProgress.Phase.GROUPING)
        )
        coroutineContext.ensureActive()
        val clusterStart = SystemClock.elapsedRealtime()
        val built = PersonOps.buildPersons(records, settings)
        val clusterMs = SystemClock.elapsedRealtime() - clusterStart

        onProgress(
            ProcessingProgress(info.durationMs, info.durationMs, frames, records.size,
                SystemClock.elapsedRealtime() - start, ProcessingProgress.Phase.SAVING)
        )
        if (settings.keepTuningData) {
            repository.saveFaceRecords(session.id, records)
        } else {
            // Zostawiamy tylko wycinki, które są pokazywane jako ujęcia osób.
            records.filter { it.id !in built.usedFaceIds }.forEach { File(sessionDir, it.cropPath).delete() }
        }

        val stats = ProcessingStats(
            wallClockMs = SystemClock.elapsedRealtime() - start,
            videoDurationMs = info.durationMs,
            framesSampled = frames,
            facesDetected = detected,
            facesAccepted = records.size,
            rejectedTooSmall = tooSmall,
            rejectedAtEdge = atEdge,
            rejectedNoLandmarks = noLandmarks,
            decodeMs = decodeMs, detectMs = detectMs, embedMs = embedMs, otherMs = otherMs,
            clusterMs = clusterMs,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        )
        return session.copy(
            video = info,
            status = SessionStatus.READY,
            persons = built.persons,
            rejectedGroups = if (settings.keepTuningData) built.rejected else emptyList(),
            stats = stats,
        )
    }

    private fun visibleFraction(box: Rect, w: Int, h: Int): Float {
        val area = box.width().toFloat() * box.height()
        if (area <= 0f) return 0f
        val vis = Rect(box)
        if (!vis.intersect(0, 0, w, h)) return 0f
        return vis.width().toFloat() * vis.height() / area
    }

    private fun grayRegion(frame: Bitmap, box: Rect): GrayImage {
        val r = Rect(box)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.isEmpty) r.set(0, 0, 1, 1)
        val w = r.width()
        val h = r.height()
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, r.left, r.top, w, h)
        return GrayImage.fromArgb(w, h, px)
    }

    /** Wycinek z marginesem ~40% wokół twarzy, kwadratowy, maks. [CROP_MAX_PX] px. */
    private fun saveCrop(frame: Bitmap, box: Rect, out: File) {
        val side = (max(box.width(), box.height()) * (1f + 2 * CROP_MARGIN)).roundToInt()
        val cx = box.centerX()
        // Lekko w dół: pod twarzą zostaje kawałek szyi/ubrania, jak na zdjęciu portretowym.
        val cy = box.centerY() + (box.height() * 0.05f).roundToInt()
        val r = Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.isEmpty) return
        // Uwaga: gdy wycinek obejmuje całą klatkę (zbliżenie), createBitmap zwraca TĘ SAMĄ bitmapę —
        // nie wolno jej wtedy zwolnić, bo klatka jest jeszcze potrzebna dla kolejnych twarzy.
        var crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val longest = max(crop.width, crop.height)
        if (longest > CROP_MAX_PX) {
            val s = CROP_MAX_PX.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(crop, (crop.width * s).roundToInt(), (crop.height * s).roundToInt(), true)
            if (scaled !== crop && crop !== frame) crop.recycle()
            crop = scaled
        }
        FileOutputStream(out).use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (crop !== frame) crop.recycle()
    }

    /** Tani skrót klatki (siatka 8×8 pikseli) do wykrywania powtórzonych klatek. */
    private fun frameHash(b: Bitmap): Long {
        var h = 1125899906842597L
        for (gy in 1..8) for (gx in 1..8) {
            h = 31 * h + b.getPixel(gx * (b.width - 1) / 9, gy * (b.height - 1) / 9)
        }
        return h
    }

    companion object {
        const val FACES_DIR = "faces"
        const val CROP_MARGIN = 0.4f
        const val CROP_MAX_PX = 400
    }
}
