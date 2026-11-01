package pl.kronikarz.poc.analysis

/** Punkt 2D niezależny od Androida (android.graphics.PointF nie działa w testach JVM). */
data class Pt(val x: Float, val y: Float)

/**
 * Wyrównanie twarzy do szablonu 112×112 używanego przez SFace / ArcFace (5 punktów:
 * oko po lewej stronie obrazu, oko po prawej, nos, kącik ust po lewej, kącik ust po prawej).
 */
object FaceAlignment {
    const val SIZE = 112

    val TEMPLATE = listOf(
        Pt(38.2946f, 51.6963f),
        Pt(73.5318f, 51.5014f),
        Pt(56.0252f, 71.7366f),
        Pt(41.5493f, 92.3655f),
        Pt(70.7299f, 92.2041f),
    )

    /**
     * Macierz afiniczna 2×3 [a, -b, tx, b, a, ty] (podobieństwo: skala + obrót + przesunięcie)
     * dopasowana metodą najmniejszych kwadratów: src (punkty w klatce) -> dst (szablon).
     */
    fun similarityTransform(src: List<Pt>, dst: List<Pt>): FloatArray {
        require(src.size == dst.size && src.size >= 2) { "Potrzeba co najmniej 2 par punktów" }
        val n = src.size
        val sxm = src.sumOf { it.x.toDouble() } / n
        val sym = src.sumOf { it.y.toDouble() } / n
        val dxm = dst.sumOf { it.x.toDouble() } / n
        val dym = dst.sumOf { it.y.toDouble() } / n
        var num1 = 0.0
        var num2 = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val sx = src[i].x - sxm
            val sy = src[i].y - sym
            val dx = dst[i].x - dxm
            val dy = dst[i].y - dym
            num1 += sx * dx + sy * dy
            num2 += sx * dy - sy * dx
            den += sx * sx + sy * sy
        }
        require(den > 1e-9) { "Punkty źródłowe się pokrywają" }
        val a = num1 / den
        val b = num2 / den
        val tx = dxm - (a * sxm - b * sym)
        val ty = dym - (b * sxm + a * sym)
        return floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat())
    }

    fun apply(m: FloatArray, p: Pt) = Pt(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    /**
     * Buduje pary punktów do wyrównania z punktów charakterystycznych. Oczy i kąciki ust są
     * sortowane po współrzędnej x, więc nie ma znaczenia, czy detektor nazywa je „lewe/prawe”
     * z perspektywy osoby czy obrazu. Brakujące usta/nos są pomijane (wystarczą oczy + 1 punkt).
     *
     * @return pary (src, dst) albo null, gdy brakuje oczu.
     */
    fun pairs(eyeA: Pt?, eyeB: Pt?, nose: Pt?, mouthA: Pt?, mouthB: Pt?): Pair<List<Pt>, List<Pt>>? {
        if (eyeA == null || eyeB == null) return null
        val (eL, eR) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val src = mutableListOf(eL, eR)
        val dst = mutableListOf(TEMPLATE[0], TEMPLATE[1])
        if (nose != null) {
            src += nose; dst += TEMPLATE[2]
        }
        if (mouthA != null && mouthB != null) {
            val (mL, mR) = if (mouthA.x <= mouthB.x) mouthA to mouthB else mouthB to mouthA
            src += mL; dst += TEMPLATE[3]
            src += mR; dst += TEMPLATE[4]
        }
        if (src.size < 3) return null
        return src to dst
    }
}
