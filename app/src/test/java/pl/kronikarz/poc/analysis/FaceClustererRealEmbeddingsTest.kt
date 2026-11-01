package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grupowanie na zapisanych, prawdziwych embeddingach SFace z filmu „Tears of Steel”
 * (CC BY 3.0, Blender Foundation): 9 osób, oznaczonych ręcznie (patrz README, „Pomiary”).
 * Dwa warianty: oryginał 720p i symulacja zgrania z VHS (niska rozdzielczość, szum, przeplot).
 *
 * Progi asercji = zmierzone wartości z zapasem; test łapie regresje algorytmu grupowania.
 */
class FaceClustererRealEmbeddingsTest {
    private val default = ClusteringParams()

    private fun check(fixture: String, maxDuplicates: Int) {
        val (faces, labels) = ClusteringEval.loadFixture(fixture)
        val m = ClusteringEval.evaluate(faces, labels, default.similarityThreshold, default.minOccurrences)
        val (hits, total) = ClusteringEval.mergeSuggestionHits(faces, labels, default.similarityThreshold, default.minOccurrences)
        println("$fixture: $m; podpowiedź scalania trafna $hits/$total")
        // Każda prawdziwa osoba ma kartę
        assertEquals(m.identitiesTotal, m.identitiesFound)
        assertEquals(9, m.identitiesTotal)
        // Żadnej karty łączącej różne osoby (tego użytkownik nie umie naprawić w POC)
        assertEquals("karty mieszane", 0, m.mixedCards)
        // Duplikaty są dopuszczalne (scalanie przyciskiem), ale nie więcej niż zmierzono
        assertTrue("duplikaty ${m.duplicateCards} > $maxDuplicates", m.duplicateCards <= maxDuplicates)
        // Najbardziej podobna karta (pierwsza na liście „To ta sama osoba co…”) zwykle jest trafna
        assertTrue("podpowiedzi $hits/$total", hits >= 0.6 * total)
    }

    @Test
    fun `film 720p`() = check("tos_hd_faces.json", maxDuplicates = 14)

    @Test
    fun `symulacja VHS`() = check("tos_vhs_faces.json", maxDuplicates = 20)

    @Test
    fun `sledzenie zmniejsza liczbe duplikatow bez mieszania osob`() {
        val (faces, labels) = ClusteringEval.loadFixture("tos_vhs_faces.json")
        val with = ClusteringEval.evaluate(faces, labels, default.similarityThreshold, tracking = true)
        val without = ClusteringEval.evaluate(faces, labels, default.similarityThreshold, tracking = false)
        println("VHS bez śledzenia: $without\nVHS ze śledzeniem: $with")
        assertTrue(with.duplicateCards < without.duplicateCards)
        assertEquals(0, with.mixedCards)
    }

    @Test
    fun `grupowanie 10 minut nagrania jest szybkie`() {
        val (faces, _) = ClusteringEval.loadFixture("tos_hd_faces.json")
        // ~4x więcej twarzy niż w filmie (tłum na weselu): powielamy z przesunięciem klatek
        val many = (0 until 4).flatMap { k -> faces.map { it.copy(id = it.id + k * 10_000, frameIndex = it.frameIndex + k * 100_000) } }
        val start = System.nanoTime()
        FaceClusterer().cluster(many.map { it.sample() })
        val ms = (System.nanoTime() - start) / 1_000_000
        println("grupowanie ${many.size} twarzy: $ms ms (JVM)")
        assertTrue("za wolno: $ms ms", ms < 10_000)
    }
}
