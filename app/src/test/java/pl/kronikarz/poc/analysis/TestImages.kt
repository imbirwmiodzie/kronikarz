package pl.kronikarz.poc.analysis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Syntetyczne „twarze” do testów: tekstura z krawędziami, rozmycie, przeplot, ekspozycja. */
object TestImages {
    /** Obraz z wyraźnymi szczegółami (paski + szachownica + gradient). */
    fun detailed(w: Int = 96, h: Int = 96, mean: Int = 128, amp: Int = 60, seed: Int = 1): GrayImage {
        val rnd = Random(seed)
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val stripes = sin(2 * PI * x / 7.0) * 0.5 + sin(2 * PI * y / 9.0) * 0.5
            val checker = if ((x / 6 + y / 6) % 2 == 0) 0.4 else -0.4
            (mean + amp * (0.6 * stripes + checker) + rnd.nextInt(-4, 5)).toInt().coerceIn(0, 255)
        }
        return GrayImage(w, h, px)
    }

    /** Rozmycie pudełkowe o promieniu r (powtarzane, ≈ gaussowskie). */
    fun blur(img: GrayImage, r: Int, passes: Int = 2): GrayImage {
        var cur = img
        repeat(passes) {
            val out = IntArray(cur.pixels.size)
            for (y in 0 until cur.height) for (x in 0 until cur.width) {
                var s = 0
                var n = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val xx = (x + dx).coerceIn(0, cur.width - 1)
                    val yy = (y + dy).coerceIn(0, cur.height - 1)
                    s += cur[xx, yy]; n++
                }
                out[y * cur.width + x] = s / n
            }
            cur = GrayImage(cur.width, cur.height, out)
        }
        return cur
    }

    /** Przeplot z ruchem: nieparzyste linie z obrazu przesuniętego o [shift] px w poziomie. */
    fun interlaced(img: GrayImage, shift: Int = 6): GrayImage {
        val out = img.pixels.copyOf()
        for (y in 1 until img.height step 2) for (x in 0 until img.width) {
            out[y * img.width + x] = img[(x + shift).coerceIn(0, img.width - 1), y]
        }
        // dodatkowo różnica jasności pól — typowa dla szybkiego ruchu/zmiany oświetlenia
        for (y in 1 until img.height step 2) for (x in 0 until img.width) {
            out[y * img.width + x] = (out[y * img.width + x] + 25).coerceAtMost(255)
        }
        return GrayImage(img.width, img.height, out)
    }

    fun scaled(img: GrayImage, f: (Int) -> Int) = GrayImage(img.width, img.height, IntArray(img.pixels.size) { f(img.pixels[it]).coerceIn(0, 255) })
}
