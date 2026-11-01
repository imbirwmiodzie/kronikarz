package pl.kronikarz.poc.analysis

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Operacje na pikselach ARGB (IntArray, wiersz po wierszu) — bez Androida, testowane na JVM.
 */
object ImageOps {

    /**
     * Usunięcie „grzebieni” przeplotu: zostawiamy jedno pole (wiersze parzyste albo nieparzyste),
     * a brakujące wiersze interpolujemy z sąsiadów. Tracimy połowę rozdzielczości pionowej,
     * ale znika poszarpanie krawędzi, które na stopklatce wygląda dużo gorzej.
     */
    fun bobDeinterlace(argb: IntArray, w: Int, h: Int, keepEven: Boolean = true): IntArray {
        require(argb.size == w * h)
        val out = argb.copyOf()
        val start = if (keepEven) 1 else 0 // wiersze do odtworzenia
        for (y in start until h step 2) {
            val up = y - 1
            val down = y + 1
            for (x in 0 until w) {
                val a = if (up >= 0) argb[up * w + x] else argb[down * w + x]
                val b = if (down < h) argb[down * w + x] else argb[up * w + x]
                out[y * w + x] = average(a, b)
            }
        }
        return out
    }

    /**
     * Skalowanie dwusześcienne (Catmull-Rom, a = -0.5). Przy powiększaniu małych wycinków daje
     * wyraźniejsze krawędzie niż dwuliniowe skalowanie robione przez system przy wyświetlaniu.
     */
    fun resizeBicubic(argb: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        require(argb.size == w * h && nw > 0 && nh > 0)
        // najpierw w poziomie, potem w pionie (rozdzielnie)
        val tmp = Array(4) { FloatArray(nw * h) }
        resampleRows(argb, w, h, nw, tmp)
        val out = Array(4) { FloatArray(nw * nh) }
        resampleColumns(tmp, nw, h, nh, out)
        return IntArray(nw * nh) { i ->
            pack(out[0][i], out[1][i], out[2][i], out[3][i])
        }
    }

    /**
     * Maska wyostrzająca: wynik = obraz + amount · (obraz − rozmycie 3×3).
     * Tylko kanały koloru; łagodnie (amount ~0.5–0.8), żeby nie wzmacniać szumu VHS.
     */
    fun unsharp(argb: IntArray, w: Int, h: Int, amount: Float): IntArray {
        require(argb.size == w * h)
        val out = IntArray(argb.size)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = argb[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val c = argb[y * w + x]
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            out[y * w + x] = (c and 0xFF000000.toInt()) or
                (clamp(cr + amount * (cr - r / 9f)) shl 16) or
                (clamp(cg + amount * (cg - g / 9f)) shl 8) or
                clamp(cb + amount * (cb - b / 9f))
        }
        return out
    }

    private fun average(a: Int, b: Int): Int {
        val aa = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF)) / 2
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bb = ((a and 0xFF) + (b and 0xFF)) / 2
        return (aa shl 24) or (r shl 16) or (g shl 8) or bb
    }

    private fun cubic(x: Float): Float {
        val t = abs(x)
        return when {
            t <= 1f -> 1.5f * t * t * t - 2.5f * t * t + 1f
            t < 2f -> -0.5f * t * t * t + 2.5f * t * t - 4f * t + 2f
            else -> 0f
        }
    }

    /** Wagi i indeksy próbek dla skalowania n -> m (współrzędne środków pikseli). */
    private fun kernel(n: Int, m: Int): Pair<IntArray, FloatArray> {
        val idx = IntArray(m * 4)
        val wts = FloatArray(m * 4)
        val scale = n.toFloat() / m
        for (i in 0 until m) {
            val src = (i + 0.5f) * scale - 0.5f
            val base = floor(src).toInt()
            var sum = 0f
            for (k in 0 until 4) {
                val p = base - 1 + k
                val wgt = cubic(src - p)
                idx[i * 4 + k] = p.coerceIn(0, n - 1)
                wts[i * 4 + k] = wgt
                sum += wgt
            }
            for (k in 0 until 4) wts[i * 4 + k] /= sum
        }
        return idx to wts
    }

    private fun resampleRows(argb: IntArray, w: Int, h: Int, nw: Int, outCh: Array<FloatArray>) {
        val (idx, wts) = kernel(w, nw)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in 0 until 4) {
                    val c = argb[row + idx[x * 4 + k]]
                    val wt = wts[x * 4 + k]
                    a += wt * ((c ushr 24) and 0xFF)
                    r += wt * ((c shr 16) and 0xFF)
                    g += wt * ((c shr 8) and 0xFF)
                    b += wt * (c and 0xFF)
                }
                val o = y * nw + x
                outCh[0][o] = a; outCh[1][o] = r; outCh[2][o] = g; outCh[3][o] = b
            }
        }
    }

    private fun resampleColumns(src: Array<FloatArray>, w: Int, h: Int, nh: Int, out: Array<FloatArray>) {
        val (idx, wts) = kernel(h, nh)
        for (y in 0 until nh) for (x in 0 until w) {
            val o = y * w + x
            for (ch in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += wts[y * 4 + k] * src[ch][idx[y * 4 + k] * w + x]
                out[ch][o] = v
            }
        }
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int =
        (clamp(a) shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
}
