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
}
