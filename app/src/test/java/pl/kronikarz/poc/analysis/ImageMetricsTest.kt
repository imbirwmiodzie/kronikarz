package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMetricsTest {
    private val sharp = TestImages.detailed()

    @Test
    fun `rozmycie obniza wariancje Laplasjanu`() {
        val blurred = TestImages.blur(sharp, 2)
        assertTrue(ImageMetrics.laplacianVariance(sharp) > 4 * ImageMetrics.laplacianVariance(blurred))
    }

    @Test
    fun `plaski obraz ma zerowa ostrosc`() {
        val flat = GrayImage(32, 32, IntArray(32 * 32) { 100 })
        assertEquals(0.0, ImageMetrics.laplacianVariance(flat), 1e-9)
        assertEquals(0.0, ImageMetrics.combingRatio(flat), 1e-9)
    }

    @Test
    fun `przeplot jest wykrywany`() {
        val natural = ImageMetrics.combingRatio(TestImages.blur(sharp, 1))
        val combed = ImageMetrics.combingRatio(TestImages.interlaced(TestImages.blur(sharp, 1)))
        assertTrue("naturalny obraz: $natural", natural < 0.7)
        assertTrue("przeplot: $combed", combed > 1.2)
    }

    @Test
    fun `ostrosc z jednego pola nie jest zawyzana przez przeplot`() {
        val base = TestImages.blur(sharp, 2)
        val combed = TestImages.interlaced(base)
        // zwykły Laplasjan „widzi” grzebienie jako ostrość…
        assertTrue(ImageMetrics.laplacianVariance(combed) > 3 * ImageMetrics.laplacianVariance(base))
        // …a liczony na jednym polu — nie (to samo pole parzyste co w oryginale)
        assertEquals(ImageMetrics.fieldSharpness(base), ImageMetrics.fieldSharpness(combed), 1e-6)
    }

    @Test
    fun `ekspozycja liczy srednia i odchylenie`() {
        val img = GrayImage(2, 2, intArrayOf(0, 0, 200, 200))
        val e = ImageMetrics.exposure(img)
        assertEquals(100.0, e.mean, 1e-9)
        assertEquals(100.0, e.stdDev, 1e-9)
    }

    @Test
    fun `zmniejszanie zachowuje proporcje i srednia`() {
        val img = GrayImage(200, 400, IntArray(200 * 400) { 77 })
        val d = img.downscaleToMaxHeight(100)
        assertEquals(100, d.height)
        assertEquals(50, d.width)
        assertTrue(d.pixels.all { it == 77 })
    }

    private fun upscale2x(img: GrayImage) = GrayImage(img.width * 2, img.height * 2, IntArray(img.width * img.height * 4) { i ->
        val x = (i % (img.width * 2)) / 2
        val y = (i / (img.width * 2)) / 2
        img[x, y]
    })

    private fun noisy(img: GrayImage, sigma: Double, seed: Int = 3): GrayImage {
        val rnd = java.util.Random(seed.toLong())
        return GrayImage(img.width, img.height, IntArray(img.pixels.size) { (img.pixels[it] + rnd.nextGaussian() * sigma).toInt().coerceIn(0, 255) })
    }

    @Test
    fun `ostrosc nie zalezy od wielkosci twarzy`() {
        // ta sama twarz 2× większa (np. zbliżenie) ma mieć podobną ostrość, nie 4× mniejszą/większą
        val face = TestImages.blur(TestImages.detailed(64, 64), 1)
        val a = ImageMetrics.normalizedSharpness(face)
        val b = ImageMetrics.normalizedSharpness(upscale2x(face))
        assertTrue("$a vs $b", b in 0.5 * a..1.5 * a)
    }

    @Test
    fun `szum VHS nie udaje ostrosci`() {
        // rozmyta twarz z mocnym szumem nie może wygrać z ostrą twarzą z lekkim szumem
        val face = TestImages.detailed(64, 64)
        val sharpClean = noisy(face, 3.0)
        val blurredNoisy = noisy(TestImages.blur(face, 2), 12.0)
        assertTrue(ImageMetrics.normalizedSharpness(sharpClean) > ImageMetrics.normalizedSharpness(blurredNoisy))
        // dla porównania: zwykły Laplasjan daje się oszukać szumowi
        assertTrue(ImageMetrics.laplacianVariance(blurredNoisy) > ImageMetrics.laplacianVariance(TestImages.blur(face, 2)) * 3)
    }
}
