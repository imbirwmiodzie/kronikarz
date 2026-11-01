package pl.kronikarz.poc.analysis

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Dane o jednej wykrytej twarzy potrzebne do oceny ujęcia. */
data class FaceObservation(
    /** Wycinek samej twarzy (prostokąt z detektora, bez marginesu), w natywnej rozdzielczości klatki. */
    val face: GrayImage,
    /** Wysokość prostokąta twarzy w pikselach klatki. */
    val faceHeightPx: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    /** Prawdopodobieństwa z ML Kit; null = brak danych. */
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
)

/** Składowe oceny (0..1) — zapisywane, żeby w trybie debug było widać, dlaczego ujęcie wygrało. */
@Serializable
data class QualityBreakdown(
    val size: Float,
    val sharpness: Float,
    val frontal: Float,
    val eyes: Float,
    val exposure: Float,
    val smile: Float,
    /** 0 = brak przeplotu, 1 = silne „grzebienie”. */
    val combing: Float,
    val total: Float,
)

data class QualityWeights(
    // Ostrość waży najwięcej: na VHS to ona decyduje, czy zdjęcie da się oglądać.
    val size: Float = 0.16f,
    val sharpness: Float = 0.33f,
    val frontal: Float = 0.18f,
    val eyes: Float = 0.13f,
    val exposure: Float = 0.15f,
    val smileBonus: Float = 0.05f,
    /** Jaką część wyniku zabiera maksymalny przeplot. */
    val combingPenalty: Float = 0.5f,
    /** Wysokość twarzy (px), od której rozmiar nie daje już punktów. */
    val fullSizePx: Float = 160f,
    /** Stała nasycenia ostrości: s / (s + K). */
    val sharpnessK: Float = 120f,
    /** Kąt (stopnie), przy którym „na wprost” spada do zera. */
    val maxAngleDeg: Float = 45f,
)

/**
 * Ocena jakości ujęcia twarzy. Czysta funkcja — bez Androida, testowana jednostkowo.
 */
class QualityScorer(private val w: QualityWeights = QualityWeights()) {

    fun score(o: FaceObservation): QualityBreakdown {
        // Normalizujemy wielkość wycinka, żeby ostrość dużych i małych twarzy była porównywalna.
        val norm = o.face.downscaleToMaxHeight(128)

        val size = clamp01(o.faceHeightPx / w.fullSizePx)

        val lap = ImageMetrics.fieldSharpness(norm)
        val sharpness = (lap / (lap + w.sharpnessK)).toFloat()

        val angle = sqrt(o.yawDeg * o.yawDeg + (1.2f * o.pitchDeg) * (1.2f * o.pitchDeg) + 0.3f * o.rollDeg * o.rollDeg)
        val frontal = clamp01(1f - angle / w.maxAngleDeg)

        val eyes = eyesScore(o.leftEyeOpen, o.rightEyeOpen)

        val exp = ImageMetrics.exposure(norm)
        val brightness = clamp01(1f - (abs(exp.mean - 128.0) / 100.0).toFloat())
        val contrast = clamp01((exp.stdDev / 45.0).toFloat())
        val exposure = 0.5f * brightness + 0.5f * contrast

        val smile = o.smiling ?: 0f

        // Przeplot liczymy na natywnej rozdzielczości (po zmniejszeniu linie pól by się wymieszały).
        val combRatio = ImageMetrics.combingRatio(o.face)
        val combing = clamp01(((combRatio - 0.75) / 1.0).toFloat())

        val base = w.size * size + w.sharpness * sharpness + w.frontal * frontal +
            w.eyes * eyes + w.exposure * exposure + w.smileBonus * smile
        val total = base * (1f - w.combingPenalty * combing)

        return QualityBreakdown(size, sharpness, frontal, eyes, exposure, smile, combing, total)
    }

    private fun eyesScore(left: Float?, right: Float?): Float {
        if (left == null && right == null) return 0.5f
        val l = left ?: right!!
        val r = right ?: left!!
        // Mrugnięcie jednym okiem też jest złym ujęciem, więc liczy się głównie gorsze oko.
        return 0.6f * min(l, r) + 0.4f * (l + r) / 2f
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
}
