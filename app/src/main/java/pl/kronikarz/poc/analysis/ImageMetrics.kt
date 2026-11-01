package pl.kronikarz.poc.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
