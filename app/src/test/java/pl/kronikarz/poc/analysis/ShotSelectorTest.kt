package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotSelectorTest {
    @Test
    fun `najlepsze ujecie i alternatywy z innych momentow`() {
        val shots = listOf(
            ScoredShot(1, 10_000, 0.90f),
            ScoredShot(2, 10_500, 0.89f), // tuż obok najlepszego — pomijamy
            ScoredShot(3, 30_000, 0.70f),
            ScoredShot(4, 31_000, 0.69f), // tuż obok #3
            ScoredShot(5, 60_000, 0.50f),
            ScoredShot(6, 90_000, 0.40f),
            ScoredShot(7, 120_000, 0.30f),
        )
        val sel = ShotSelector.select(shots, alternates = 3, minGapMs = 3_000)
        assertEquals(1, sel.best.faceId)
        assertEquals(listOf(3, 5, 6), sel.alternates.map { it.faceId })
        val all = listOf(sel.best) + sel.alternates
        for (a in all) for (b in all) if (a !== b) assertTrue(kotlin.math.abs(a.timestampMs - b.timestampMs) >= 3_000)
    }

    @Test
    fun `jedno ujecie = brak alternatyw`() {
        val sel = ShotSelector.select(listOf(ScoredShot(1, 0, 0.5f)))
        assertEquals(1, sel.best.faceId)
        assertTrue(sel.alternates.isEmpty())
    }
}
