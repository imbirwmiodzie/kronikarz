package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {
    private fun toArgb(g: GrayImage) = IntArray(g.pixels.size) { val v = g.pixels[it]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    private fun toGray(argb: IntArray, w: Int, h: Int) = GrayImage.fromArgb(w, h, argb)

    @Test
    fun `usuniecie przeplotu likwiduje grzebienie i zachowuje wybrane pole`() {
        val base = TestImages.blur(TestImages.detailed(), 1)
        val combed = TestImages.interlaced(base)
        val argb = toArgb(combed)
        val clean = ImageOps.bobDeinterlace(argb, combed.width, combed.height, keepEven = true)
        val before = ImageMetrics.combingRatio(combed)
        val after = ImageMetrics.combingRatio(toGray(clean, combed.width, combed.height))
        assertTrue("przed $before, po $after", before > 1.2 && after < 0.7)
        // wiersze parzyste bez zmian
        for (y in 0 until combed.height step 2) for (x in 0 until combed.width) {
            assertEquals(argb[y * combed.width + x], clean[y * combed.width + x])
        }
    }

    @Test
    fun `skalowanie dwuszescienne - rozmiar, staly kolor, przezroczystosc`() {
        val w = 10
        val h = 7
        val src = IntArray(w * h) { 0xFF336699.toInt() }
        val out = ImageOps.resizeBicubic(src, w, h, 40, 28)
        assertEquals(40 * 28, out.size)
        assertTrue(out.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun `skalowanie zachowuje monotoniczny gradient i jego zakres`() {
        val w = 16
        val src = IntArray(w * 2) { i -> val v = (i % w) * 16; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val out = ImageOps.resizeBicubic(src, w, 2, 64, 4)
        val row = (0 until 64).map { (out[it] shr 16) and 0xFF }
        assertTrue(row.first() <= 5 && row.last() >= 235)
        // brak „dzwonienia” poza zakresem źródła i rosnący przebieg (z tolerancją zaokrągleń)
        row.zipWithNext().forEach { (a, b) -> assertTrue("$a > $b", b >= a - 1) }
    }

    @Test
    fun `wyostrzenie zwieksza ostrosc rozmytego obrazu i nie zmienia plaskiego`() {
        val blurred = TestImages.blur(TestImages.detailed(), 1)
        val sharp = ImageOps.unsharp(toArgb(blurred), blurred.width, blurred.height, 0.8f)
        assertTrue(ImageMetrics.laplacianVariance(toGray(sharp, blurred.width, blurred.height)) > 1.3 * ImageMetrics.laplacianVariance(blurred))
        val flat = IntArray(64) { 0xFF808080.toInt() }
        assertTrue(ImageOps.unsharp(flat, 8, 8, 0.8f).all { it == 0xFF808080.toInt() })
    }
}
