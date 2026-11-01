package pl.kronikarz.poc.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import pl.kronikarz.poc.analysis.FaceAlignment
import pl.kronikarz.poc.analysis.FaceObservation
import pl.kronikarz.poc.analysis.GrayImage
import pl.kronikarz.poc.analysis.QualityScorer
import pl.kronikarz.poc.analysis.ImageOps
import pl.kronikarz.poc.analysis.VectorMath
import pl.kronikarz.poc.data.FaceRecord
import pl.kronikarz.poc.data.FaceShot
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.VideoInfo
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
    /** Etap doszukiwania ostrzejszych ujęć: ile ujęć sprawdzono / ile w sumie. */
    val refineDone: Int = 0,
    val refineTotal: Int = 0,
    /** Czy po klatkach będzie etap doszukiwania (wtedy klatki to 0–80% paska). */
    val refineEnabled: Boolean = false,
) {
    enum class Phase { FRAMES, GROUPING, REFINING, SAVING }

    val fraction: Float
        get() {
            val frames = if (durationMs <= 0) 0f else (processedMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (!refineEnabled) return frames
            return when (phase) {
                Phase.FRAMES -> FRAMES_SHARE * frames
                Phase.GROUPING -> FRAMES_SHARE
                Phase.REFINING -> FRAMES_SHARE + (1 - FRAMES_SHARE) * (if (refineTotal == 0) 1f else refineDone.toFloat() / refineTotal)
                Phase.SAVING -> 1f
            }
        }

    private companion object {
        const val FRAMES_SHARE = 0.8f
    }

    /** Szacowany pozostały czas na podstawie dotychczasowego tempa. */
    val etaMs: Long? get() = if (fraction < 0.03f) null else (elapsedMs / fraction - elapsedMs).toLong()
}

/**
 * Cały potok: klatki -> twarze -> filtry -> embedding + ocena jakości -> wycinki -> grupowanie -> osoby
 * -> doszukanie ostrzejszych ujęć w sąsiednich klatkach. Schemat: README, „Schemat przetwarzania”.
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
        var framesFailed = 0
        var framesDuplicate = 0
        val records = mutableListOf<FaceRecord>()

        // Względny minimalny rozmiar dla ML Kit (szerokość głowy / szerokość obrazu) — trochę
        // poniżej progu w pikselach, żeby to nasz filtr decydował, a detektor nie gubił twarzy.
        val frameW = min(info.width.takeIf { it > 0 } ?: settings.maxFrameWidth, settings.maxFrameWidth)
        val relMin = (settings.minFaceHeightPx * 0.8f / frameW).coerceIn(0.02f, 0.5f)

        val refineOn = settings.refineWindowMs > 0
        var built: PersonOps.BuildResult? = null
        var clusterMs = 0L
        var refine = RefineResult(emptyList(), 0, 0, 0)

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
                                saveCrop(frame, f.box, File(facesDir, cropName), deinterlace = quality.combing > DEINTERLACE_ABOVE)
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
                        } else {
                            framesDuplicate++
                        }
                        frame.recycle()
                    } else {
                        framesFailed++
                    }
                    t += settings.sampleIntervalMs
                    onProgress(
                        ProcessingProgress(
                            processedMs = min(t, info.durationMs), durationMs = info.durationMs,
                            framesProcessed = frames, facesFound = records.size,
                            elapsedMs = SystemClock.elapsedRealtime() - start,
                            phase = ProcessingProgress.Phase.FRAMES,
                            refineEnabled = refineOn,
                        )
                    )
                }

                onProgress(
                    ProcessingProgress(info.durationMs, info.durationMs, frames, records.size,
                        SystemClock.elapsedRealtime() - start, ProcessingProgress.Phase.GROUPING, refineEnabled = refineOn)
                )
                coroutineContext.ensureActive()
                val clusterStart = SystemClock.elapsedRealtime()
                val b = PersonOps.buildPersons(records, settings)
                clusterMs = SystemClock.elapsedRealtime() - clusterStart
                built = b

                if (refineOn && b.persons.isNotEmpty()) {
                    refine = refineShots(b.persons, records, settings, info, detector, embedder, facesDir) { done, total ->
                        onProgress(
                            ProcessingProgress(info.durationMs, info.durationMs, frames, records.size,
                                SystemClock.elapsedRealtime() - start, ProcessingProgress.Phase.REFINING,
                                refineDone = done, refineTotal = total, refineEnabled = true)
                        )
                    }
                }
            }
        }
        val result = built!!
        val persons = if (refine.persons.isNotEmpty()) refine.persons else result.persons

        onProgress(
            ProcessingProgress(info.durationMs, info.durationMs, frames, records.size,
                SystemClock.elapsedRealtime() - start, ProcessingProgress.Phase.SAVING, refineEnabled = refineOn)
        )
        if (settings.keepTuningData) {
            repository.saveFaceRecords(session.id, records)
        } else {
            // Zostawiamy tylko wycinki, które są pokazywane jako ujęcia osób.
            records.filter { it.id !in result.usedFaceIds }.forEach { File(sessionDir, it.cropPath).delete() }
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
            framesFailed = framesFailed,
            framesDuplicate = framesDuplicate,
            refineMs = refine.ms,
            refineShots = refine.shots,
            refineImproved = refine.improved,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        )
        return session.copy(
            video = info,
            status = SessionStatus.READY,
            persons = persons,
            rejectedGroups = if (settings.keepTuningData) result.rejected else emptyList(),
            stats = stats,
        )
    }

    private data class RefineResult(val persons: List<Person>, val shots: Int, val improved: Int, val ms: Long)

    /**
     * Doszukiwanie ostrzejszych ujęć. Próbkujemy co 0,5 s, więc wybrane ujęcie bywa poruszone albo
     * z „grzebieniami” przeplotu, a klatka obok jest ostra. Dla każdego ujęcia (najlepsze + alternatywy)
     * dekodujemy KAŻDĄ klatkę w oknie ±refineWindowMs, znajdujemy tę samą twarz (to samo miejsce
     * kadru + podobny embedding) i bierzemy klatkę z najwyższą oceną (w której ostrość waży najwięcej).
     * Osobny MediaMetadataRetriever, bo dekoder sekwencyjny nie umie się cofać.
     */
    private suspend fun refineShots(
        persons: List<Person>,
        records: List<FaceRecord>,
        settings: ProcessingSettings,
        info: VideoInfo,
        detector: FaceDetectorEngine,
        embedder: FaceEmbedder,
        facesDir: File,
        onStep: (done: Int, total: Int) -> Unit,
    ): RefineResult {
        val t0 = SystemClock.elapsedRealtime()
        val source = runCatching {
            RetrieverFrameSource(context, Uri.parse(info.uri), exact = true, maxWidth = settings.maxFrameWidth)
        }.getOrElse {
            Log.w(TAG, "Doszukiwanie ujęć pominięte: nie można otworzyć wideo", it)
            return RefineResult(emptyList(), 0, 0, 0)
        }
        val byId = records.associateBy { it.id }
        val frameMs = if (info.frameRate > 0f) 1000f / info.frameRate else 40f
        val steps = (settings.refineWindowMs / frameMs).roundToInt().coerceIn(1, MAX_REFINE_STEPS)
        val total = persons.sumOf { 1 + it.alternateShots.size }
        var done = 0
        var improved = 0
        onStep(0, total)

        source.use { src ->
            val out = persons.map { p ->
                val shots = (listOf(p.bestShot) + p.alternateShots).map { shot ->
                    coroutineContext.ensureActive()
                    val better = runCatching { refineOne(shot, byId[shot.faceId], steps, frameMs, info, settings, src, detector, embedder, facesDir) }
                        .onFailure { Log.w(TAG, "Doszukiwanie ujęcia ${shot.faceId} nie powiodło się", it) }
                        .getOrNull()
                    done++
                    onStep(done, total)
                    if (better != null) improved++
                    better ?: shot
                }
                // po doszukaniu alternatywa mogła przegonić dotychczasowe najlepsze ujęcie
                val best = shots.maxBy { it.quality }
                p.copy(bestShot = best, alternateShots = shots.filter { it !== best })
            }
            return RefineResult(out, total, improved, SystemClock.elapsedRealtime() - t0)
        }
    }

    /** Zwraca lepsze ujęcie albo null, gdy w oknie nie ma lepszej klatki tej samej twarzy. */
    private fun refineOne(
        shot: FaceShot,
        record: FaceRecord?,
        steps: Int,
        frameMs: Float,
        info: VideoInfo,
        settings: ProcessingSettings,
        source: FrameSource,
        detector: FaceDetectorEngine,
        embedder: FaceEmbedder,
        facesDir: File,
    ): FaceShot? {
        record ?: return null
        val refBox = Rect(record.left, record.top, record.right, record.bottom)
        var bestQ = shot.quality
        var bestT = -1L
        val outName = "f_${shot.faceId}_r.jpg"
        for (k in -steps..steps) {
            if (k == 0) continue
            val t = record.timestampMs + (k * frameMs).roundToInt()
            if (t < 0 || t >= info.durationMs) continue
            val frame = source.frameAt(t) ?: continue
            try {
                val match = detector.detect(frame)
                    .filter { it.box.height() >= settings.minFaceHeightPx && visibleFraction(it.box, frame.width, frame.height) >= 0.92f }
                    .map { it to iou(it.box, refBox) }
                    .filter { it.second >= REFINE_MIN_IOU }
                    .maxByOrNull { it.second }?.first ?: continue
                val pairs = FaceAlignment.pairs(match.leftEye, match.rightEye, match.nose, match.mouthLeft, match.mouthRight) ?: continue
                val emb = embedder.embed(frame, FaceAlignment.similarityTransform(pairs.first, pairs.second))
                // ta sama osoba? (w oknie ±160 ms bywa cięcie montażowe)
                if (VectorMath.dot(emb, VectorMath.normalize(record.embedding)) < REFINE_MIN_SIMILARITY) continue
                val q = scorer.score(
                    FaceObservation(
                        face = grayRegion(frame, match.box),
                        faceHeightPx = match.box.height().toFloat(),
                        yawDeg = match.yaw, pitchDeg = match.pitch, rollDeg = match.roll,
                        leftEyeOpen = match.leftEyeOpen, rightEyeOpen = match.rightEyeOpen,
                        smiling = match.smiling,
                    )
                )
                if (q.total > bestQ + MIN_IMPROVEMENT) {
                    saveCrop(frame, match.box, File(facesDir, outName), deinterlace = q.combing > DEINTERLACE_ABOVE)
                    bestQ = q.total
                    bestT = t
                }
            } finally {
                frame.recycle()
            }
        }
        if (bestT < 0) return null
        return FaceShot(shot.faceId, "$FACES_DIR/$outName", bestT, bestQ)
    }

    private fun iou(a: Rect, b: Rect): Float {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return 0f
        val i = inter.width().toFloat() * inter.height()
        val u = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - i
        return if (u <= 0f) 0f else i / u
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
    private fun saveCrop(frame: Bitmap, box: Rect, out: File, deinterlace: Boolean = false) {
        val side = (max(box.width(), box.height()) * (1f + 2 * CROP_MARGIN)).roundToInt()
        val cx = box.centerX()
        // Lekko w dół: pod twarzą zostaje kawałek szyi/ubrania, jak na zdjęciu portretowym.
        val cy = box.centerY() + (box.height() * 0.05f).roundToInt()
        val r = Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.isEmpty) return
        // Uwaga: gdy wycinek obejmuje całą klatkę (zbliżenie), createBitmap zwraca TĘ SAMĄ bitmapę —
        // nie wolno jej wtedy zwolnić, bo klatka jest jeszcze potrzebna dla kolejnych twarzy.
        var crop = if (deinterlace) {
            // jedno pole liczone względem wierszy KLATKI (parzystość r.top), drugie interpolowane
            val px = IntArray(r.width() * r.height())
            frame.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
            val clean = ImageOps.bobDeinterlace(px, r.width(), r.height(), keepEven = r.top % 2 == 0)
            Bitmap.createBitmap(clean, r.width(), r.height(), Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        }
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
        private const val TAG = "VideoProcessor"
        /** Od jakiej oceny przeplotu (0–1) zapisujemy wycinek z jednego pola. */
        const val DEINTERLACE_ABOVE = 0.15f
        private const val MAX_REFINE_STEPS = 12
        private const val REFINE_MIN_IOU = 0.3f
        private const val REFINE_MIN_SIMILARITY = 0.35f
        /** Minimalna poprawa oceny, żeby podmienić ujęcie (szum oceny). */
        private const val MIN_IMPROVEMENT = 0.01f
    }
}
