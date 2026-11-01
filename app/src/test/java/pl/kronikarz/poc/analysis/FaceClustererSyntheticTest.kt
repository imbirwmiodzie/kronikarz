package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Grupowanie na syntetycznych embeddingach (tożsamość = losowy kierunek + szum). */
class FaceClustererSyntheticTest {

    private fun randomUnit(rnd: Random, dim: Int = 128) = VectorMath.normalize(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })

    /** Szum dobrany tak, żeby podobieństwo do centrum ≈ 0.6–0.7, jak w realnych danych z VHS. */
    private fun noisy(center: FloatArray, rnd: Random, noise: Float) =
        VectorMath.normalize(FloatArray(center.size) { center[it] + noise * (rnd.nextFloat() * 2 - 1) })

    @Test
    fun `rozdziela piec osob i odrzuca szum`() {
        val rnd = Random(7)
        val centers = List(5) { randomUnit(rnd) }
        val faces = mutableListOf<FaceSample>()
        val truth = HashMap<Int, Int>()
        var id = 0
        var frame = 0
        centers.forEachIndexed { person, c ->
            repeat(20 + person * 5) {
                truth[id] = person
                faces += FaceSample(id++, frame++, noisy(c, rnd, 0.11f))
            }
        }
        // 4 przypadkowe twarze w tle — każda inna
        repeat(4) { faces += FaceSample(id++, frame++, randomUnit(rnd)) }

        val r = FaceClusterer(ClusteringParams(similarityThreshold = 0.4f, minOccurrences = 3)).cluster(faces.shuffled(rnd))
        assertEquals(5, r.clusters.size)
        r.clusters.forEach { c ->
            val labels = c.memberIds.map { truth[it] }.toSet()
            assertEquals("grupa nieczysta: $labels", 1, labels.size)
        }
        assertEquals(4, r.rejected.sumOf { it.size })
    }

    @Test
    fun `twarze z tej samej klatki nie trafiaja do jednej osoby`() {
        val rnd = Random(3)
        val c = randomUnit(rnd)
        // dwie „bardzo podobne” osoby (np. bliźnięta) zawsze razem w kadrze
        val faces = (0 until 10).flatMap { f ->
            listOf(FaceSample(2 * f, f, noisy(c, rnd, 0.05f)), FaceSample(2 * f + 1, f, noisy(c, rnd, 0.05f)))
        }
        val r = FaceClusterer(ClusteringParams(similarityThreshold = 0.3f, minOccurrences = 1)).cluster(faces)
        r.clusters.forEach { cl ->
            val frames = cl.memberIds.map { it / 2 }
            assertEquals("ta sama klatka w jednej grupie", frames.size, frames.toSet().size)
        }
        assertTrue(r.clusters.size >= 2)
    }

    @Test
    fun `wyzszy prog daje wiecej grup`() {
        val rnd = Random(11)
        val centers = List(3) { randomUnit(rnd) }
        val faces = centers.flatMapIndexed { p, c -> (0 until 30).map { FaceSample(p * 100 + it, p * 100 + it, noisy(c, rnd, 0.14f)) } }
        val loose = FaceClusterer(ClusteringParams(similarityThreshold = 0.3f, minOccurrences = 1)).cluster(faces)
        val strict = FaceClusterer(ClusteringParams(similarityThreshold = 0.85f, minOccurrences = 1)).cluster(faces)
        assertEquals(3, loose.clusters.size)
        assertTrue(strict.clusters.size > loose.clusters.size)
    }

    @Test
    fun `pusta lista`() {
        val r = FaceClusterer().cluster(emptyList())
        assertTrue(r.clusters.isEmpty() && r.rejected.isEmpty())
    }

    @Test
    fun `srednie podobienstwo grup`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(1f, FaceClusterer.averageSimilarity(listOf(a), listOf(a)), 1e-6f)
        assertEquals(0f, FaceClusterer.averageSimilarity(listOf(a), listOf(b)), 1e-6f)
        assertEquals(0.5f, FaceClusterer.averageSimilarity(listOf(a, b), listOf(a)), 1e-6f)
    }
}
