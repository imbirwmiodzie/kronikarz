package pl.kronikarz.poc.analysis

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Obraz w skali szarości (luminancja 0..255), niezależny od Androida, żeby dało się go testować na JVM.
 */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Pusty obraz" }
        require(pixels.size == width * height) { "Zły rozmiar bufora" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Co druga linia (jedno pole przeplotu). */
    fun field(even: Boolean = true): GrayImage {
        val start = if (even) 0 else 1
        val h = (height - start + 1) / 2
        val out = IntArray(width * h)
        for (y in 0 until h) {
            System.arraycopy(pixels, (start + 2 * y) * width, out, y * width, width)
        }
        return GrayImage(width, h, out)
    }

    /** Zmniejszenie uśrednianiem pól (box filter), jeśli obraz jest wyższy niż [maxHeight]. */
    fun downscaleToMaxHeight(maxHeight: Int): GrayImage {
        if (height <= maxHeight) return this
        val scale = height.toDouble() / maxHeight
        val newW = max(1, (width / scale).toInt())
        val newH = maxHeight
        val out = IntArray(newW * newH)
        for (ny in 0 until newH) {
            val y0 = (ny * scale).toInt()
            val y1 = min(height, ((ny + 1) * scale).toInt().coerceAtLeast(y0 + 1))
            for (nx in 0 until newW) {
                val x0 = (nx * scale).toInt()
                val x1 = min(width, ((nx + 1) * scale).toInt().coerceAtLeast(x0 + 1))
                var sum = 0L
                for (y in y0 until y1) for (x in x0 until x1) sum += pixels[y * width + x]
                out[ny * newW + nx] = (sum / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }
        return GrayImage(newW, newH, out)
    }

    companion object {
        /** Z pikseli ARGB (np. z Bitmap.getPixels). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = IntArray(argb.size)
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            return GrayImage(width, height, out)
        }
    }
}

object ImageMetrics {

    /** Wariancja Laplasjanu (jądro 4-sąsiedztwa). Większa = ostrzejszy obraz. */
    fun laplacianVariance(img: GrayImage): Double {
        if (img.width < 3 || img.height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until img.height - 1) {
            for (x in 1 until img.width - 1) {
                val l = (img[x - 1, y] + img[x + 1, y] + img[x, y - 1] + img[x, y + 1] - 4 * img[x, y]).toDouble()
                sum += l
                sumSq += l * l
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * Ostrość odporna na przeplot: Laplasjan liczony na jednym polu (co druga linia).
     * „Grzebienie” przeplotu dają naprzemienne linie, które sztucznie zawyżają zwykły Laplasjan
     * — na pojedynczym polu ich nie ma.
     */
    fun fieldSharpness(img: GrayImage): Double =
        if (img.height >= 6) laplacianVariance(img.field(even = true)) else laplacianVariance(img)

    /**
     * Ostrość twarzy odporna na szum VHS, przeplot i wielkość twarzy (używana w ocenie ujęcia):
     * 1. jedno pole (co druga linia) — grzebienie przeplotu nie udają szczegółów,
     * 2. przeskalowanie twarzy do stałej wysokości [height] px — mała i duża twarz są porównywalne,
     * 3. lekkie rozmycie gaussowskie σ = [sigma] — szum taśmy nie udaje szczegółów,
     * 4. wariancja Laplasjanu.
     *
     * Sprawdzone na twarzach z symulacji VHS filmu testowego względem ostrości tych samych twarzy
     * w czystej wersji: zgodność kolejności w parach tej samej osoby 89% (wcześniejsza wersja bez
     * stałej skali i rozmycia: 38%, czyli gorzej niż losowo — nagradzała szum i małe twarze).
     */
    fun normalizedSharpness(img: GrayImage, height: Int = 64, sigma: Double = 0.7): Double {
        val src = if (img.height >= 12) img.field(even = true) else img
        val tw = max(4, (img.width.toDouble() * height / img.height).roundToInt())
        var px = DoubleArray(src.pixels.size) { src.pixels[it].toDouble() }
        px = resampleRows(px, src.width, src.height, tw)
        px = resampleCols(px, tw, src.height, height)
        px = gaussian(px, tw, height, sigma)
        return laplacianVariance(px, tw, height)
    }

    private fun laplacianVariance(px: DoubleArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = px[i - 1] + px[i + 1] + px[i - w] + px[i + w] - 4 * px[i]
            sum += l; sumSq += l * l; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Skalowanie 1D: uśrednianie powierzchni przy zmniejszaniu, Catmull-Rom przy powiększaniu. */
    private fun resample1D(get: (Int) -> Double, n: Int, m: Int, out: (Int, Double) -> Unit) {
        if (m < n) {
            val scale = n.toDouble() / m
            for (i in 0 until m) {
                val a = i * scale
                val b = (i + 1) * scale
                var acc = 0.0
                var k = floor(a).toInt()
                while (k < b && k < n) {
                    val lo = max(a, k.toDouble())
                    val hi = minOf(b, k + 1.0)
                    if (hi > lo) acc += get(k) * (hi - lo)
                    k++
                }
                out(i, acc / scale)
            }
        } else {
            val scale = n.toDouble() / m
            for (i in 0 until m) {
                val s = (i + 0.5) * scale - 0.5
                val base = floor(s).toInt()
                var acc = 0.0
                var wsum = 0.0
                for (k in -1..2) {
                    val p = base + k
                    val wgt = catmullRom(s - p)
                    acc += wgt * get(p.coerceIn(0, n - 1))
                    wsum += wgt
                }
                out(i, acc / wsum)
            }
        }
    }

    private fun catmullRom(x: Double): Double {
        val t = abs(x)
        return when {
            t <= 1 -> 1.5 * t * t * t - 2.5 * t * t + 1
            t < 2 -> -0.5 * t * t * t + 2.5 * t * t - 4 * t + 2
            else -> 0.0
        }
    }

    private fun resampleRows(px: DoubleArray, w: Int, h: Int, nw: Int): DoubleArray {
        val out = DoubleArray(nw * h)
        for (y in 0 until h) resample1D({ px[y * w + it] }, w, nw) { i, v -> out[y * nw + i] = v }
        return out
    }

    private fun resampleCols(px: DoubleArray, w: Int, h: Int, nh: Int): DoubleArray {
        val out = DoubleArray(w * nh)
        for (x in 0 until w) resample1D({ px[it * w + x] }, h, nh) { i, v -> out[i * w + x] = v }
        return out
    }

    private fun gaussian(px: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        if (sigma <= 0) return px
        val r = ceil(3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = k.sum()
        for (i in k.indices) k[i] /= ks
        val tmp = DoubleArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0.0
            for (j in -r..r) acc += k[j + r] * px[y * w + (x + j).coerceIn(0, w - 1)]
            tmp[y * w + x] = acc
        }
        val out = DoubleArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0.0
            for (j in -r..r) acc += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = acc
        }
        return out
    }

    /**
     * Miara „grzebieni” przeplotu. Porównuje drugą pochodną pionową między sąsiednimi liniami
     * (różne pola) z drugą pochodną między liniami tego samego pola (co 2 linie).
     * Najpierw wygładzamy poziomo (5 px): szum VHS słabnie, a grzebienie (przesunięte całe linie) zostają.
     * Zmierzone na wycinkach twarzy: czysty obraz ~0.35–0.5, szum VHS bez ruchu ~0.6–0.8,
     * lekkie grzebienie ~1.0, wyraźne > 1.5.
     */
    fun combingRatio(img: GrayImage): Double {
        if (img.height < 5) return 0.0
        val w = img.width
        val sm = IntArray(img.pixels.size)
        for (y in 0 until img.height) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0
                var n = 0
                for (dx in -2..2) {
                    val xx = x + dx
                    if (xx in 0 until w) {
                        s += img.pixels[row + xx]; n++
                    }
                }
                sm[row + x] = s * 4 / n // x4: zachowujemy precyzję bez liczb zmiennoprzecinkowych
            }
        }
        var inter = 0.0
        var intra = 0.0
        for (y in 2 until img.height - 2) {
            for (x in 0 until w) {
                val c = sm[y * w + x]
                inter += abs(sm[(y - 1) * w + x] + sm[(y + 1) * w + x] - 2 * c)
                intra += abs(sm[(y - 2) * w + x] + sm[(y + 2) * w + x] - 2 * c)
            }
        }
        return inter / (intra + 4.0 * w)
    }

    data class Exposure(val mean: Double, val stdDev: Double)

    fun exposure(img: GrayImage): Exposure {
        var sum = 0.0
        var sumSq = 0.0
        for (p in img.pixels) {
            sum += p
            sumSq += p.toDouble() * p
        }
        val n = img.pixels.size
        val mean = sum / n
        return Exposure(mean, sqrt(max(0.0, sumSq / n - mean * mean)))
    }
}
