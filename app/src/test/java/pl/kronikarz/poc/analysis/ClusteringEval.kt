package pl.kronikarz.poc.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Format pliku faces.json z tools/extract_faces.py (i fixture w src/test/resources). */
@Serializable
data class EvalFace(
    val id: Int,
    val frameIndex: Int,
    val timestampMs: Long,
    val embedding: FloatArray,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    fun sample() = FaceSample(id, frameIndex, embedding, if (right > left) Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) else null)
}

@Serializable
data class EvalFile(val video: String = "", val faces: List<EvalFace>)

data class EvalMetrics(
    val threshold: Float,
    /** Osoby (prawdziwe, z >= minOcc twarzami), które dostały co najmniej jedną kartę. */
    val identitiesFound: Int,
    val identitiesTotal: Int,
    val cards: Int,
    /** Karty-duplikaty: dodatkowe karty tej samej osoby (do scalenia przyciskiem). */
    val duplicateCards: Int,
    /** Karty łączące różne osoby (co najmniej 2 twarze lub 10% z innej osoby). */
    val mixedCards: Int,
    /** Twarze na kartach, które nie należą do większościowej osoby karty. */
    val wrongFaces: Int,
    /** Karty z samych twarzy oznaczonych jako „inne/tło” (do ukrycia). */
    val noiseCards: Int,
    val facesOnCards: Int,
    /** Rozmiary kart każdej osoby (do diagnostyki duplikatów). */
    val cardSizes: Map<String, List<Int>> = emptyMap(),
) {
    override fun toString() =
        "próg %.2f: kart %d, osoby %d/%d, duplikaty %d, karty mieszane %d (błędne twarze %d z %d), karty-szum %d"
            .format(threshold, cards, identitiesFound, identitiesTotal, duplicateCards, mixedCards, wrongFaces, facesOnCards, noiseCards)
}

@Serializable
data class FixtureFace(val id: Int, val f: Int, val t: Long, val b: List<Int>, val l: String, val e: String)

@Serializable
data class Fixture(val source: String, val faces: List<FixtureFace>)

object ClusteringEval {
    val json = Json { ignoreUnknownKeys = true }

    /** Wczytuje fixture z src/test/resources (embeddingi int8/base64). */
    fun loadFixture(name: String): Pair<List<EvalFace>, Map<Int, String>> {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() }
        val fx = json.decodeFromString(Fixture.serializer(), text)
        val faces = fx.faces.map { f ->
            val bytes = java.util.Base64.getDecoder().decode(f.e)
            EvalFace(f.id, f.f, f.t, FloatArray(bytes.size) { bytes[it] / 127f }, f.b[0], f.b[1], f.b[2], f.b[3])
        }
        return faces to fx.faces.associate { it.id to it.l }
    }

    /**
     * Trafność podpowiedzi scalania: dla każdej karty-duplikatu sprawdzamy, czy najbardziej podobna
     * (po średnim embeddingu) inna karta to ta sama osoba. Zwraca (trafione, wszystkie).
     */
    fun mergeSuggestionHits(faces: List<EvalFace>, labels: Map<Int, String>, threshold: Float, minOcc: Int = 3): Pair<Int, Int> {
        val byId = faces.associateBy { it.id }
        val clusters = FaceClusterer(ClusteringParams(similarityThreshold = threshold, minOccurrences = minOcc))
            .cluster(faces.map { it.sample() }).clusters
        val major = clusters.map { c -> c.memberIds.mapNotNull { labels[it]?.takeIf { l -> l != "?" } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
        val centroids = clusters.map { c ->
            val sum = FloatArray(128)
            c.memberIds.forEach { id -> VectorMath.normalize(byId.getValue(id).embedding).forEachIndexed { i, v -> sum[i] += v } }
            VectorMath.normalize(sum)
        }
        var hits = 0
        var total = 0
        for (i in clusters.indices) {
            val me = major[i] ?: continue
            if (major.indices.none { it != i && major[it] == me }) continue // osoba ma tylko jedną kartę
            total++
            val best = clusters.indices.filter { it != i }.maxBy { VectorMath.dot(centroids[i], centroids[it]) }
            if (major[best] == me) hits++
        }
        return hits to total
    }

    /** Etykiety: id twarzy -> nazwa osoby; "?" lub brak = tło/nieznana. */
    fun evaluate(
        faces: List<EvalFace>,
        labels: Map<Int, String>,
        threshold: Float,
        minOcc: Int = 3,
        tracking: Boolean = true,
    ): EvalMetrics {
        val params = ClusteringParams(similarityThreshold = threshold, minOccurrences = minOcc)
        val result = FaceClusterer(if (tracking) params else params.copy(trackLinkThreshold = null))
            .cluster(faces.map { it.sample() })
        val known = labels.filterValues { it != "?" }
        val truthCounts = known.values.groupingBy { it }.eachCount().filterValues { it >= minOcc }
        val cardsPerIdentity = HashMap<String, Int>()
        val sizes = HashMap<String, MutableList<Int>>()
        var mixed = 0
        var wrong = 0
        var noise = 0
        for (c in result.clusters) {
            val ls = c.memberIds.mapNotNull { known[it] }
            if (ls.isEmpty()) {
                noise++; continue
            }
            val counts = ls.groupingBy { it }.eachCount()
            val (major, n) = counts.maxBy { it.value }
            cardsPerIdentity[major] = (cardsPerIdentity[major] ?: 0) + 1
            sizes.getOrPut(major) { mutableListOf() } += c.size
            val others = ls.size - n
            wrong += others
            if (others >= 2 || others > 0.1 * ls.size) mixed++
        }
        val found = truthCounts.keys.count { (cardsPerIdentity[it] ?: 0) > 0 }
        val dups = cardsPerIdentity.values.sumOf { it - 1 }
        return EvalMetrics(threshold, found, truthCounts.size, result.clusters.size, dups, mixed, wrong, noise, result.clusters.sumOf { it.size }, sizes)
    }
}
