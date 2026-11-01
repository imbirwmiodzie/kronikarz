package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceAlignmentTest {

    @Test
    fun `odzyskuje znane podobienstwo`() {
        // dst = szablon; src = szablon obrócony o 20°, przeskalowany x0.5 i przesunięty
        val ang = Math.toRadians(20.0)
        val s = 0.5
        val src = FaceAlignment.TEMPLATE.map {
            Pt(
                (s * (cos(ang) * it.x - sin(ang) * it.y) + 300).toFloat(),
                (s * (sin(ang) * it.x + cos(ang) * it.y) + 120).toFloat(),
            )
        }
        val m = FaceAlignment.similarityTransform(src, FaceAlignment.TEMPLATE)
        src.zip(FaceAlignment.TEMPLATE).forEach { (a, b) ->
            val p = FaceAlignment.apply(m, a)
            assertEquals(b.x, p.x, 1e-3f)
            assertEquals(b.y, p.y, 1e-3f)
        }
    }

    @Test
    fun `kolejnosc lewe-prawe nie ma znaczenia`() {
        val a = FaceAlignment.pairs(Pt(70f, 50f), Pt(40f, 50f), Pt(55f, 70f), Pt(68f, 90f), Pt(42f, 90f))!!
        val b = FaceAlignment.pairs(Pt(40f, 50f), Pt(70f, 50f), Pt(55f, 70f), Pt(42f, 90f), Pt(68f, 90f))!!
        assertEquals(a, b)
        assertEquals(Pt(40f, 50f), a.first[0])
    }

    @Test
    fun `brak oka = brak wyrownania, brak ust = 3 punkty`() {
        assertNull(FaceAlignment.pairs(null, Pt(1f, 1f), Pt(2f, 2f), null, null))
        val p = FaceAlignment.pairs(Pt(40f, 50f), Pt(70f, 50f), Pt(55f, 70f), null, null)
        assertNotNull(p)
        assertEquals(3, p!!.first.size)
    }
}
