package pl.kronikarz.poc.analysis

import kotlin.math.sqrt

object VectorMath {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        if (n < 1e-12f) return v.copyOf()
        return FloatArray(v.size) { v[it] / n }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun cosine(a: FloatArray, b: FloatArray): Float = dot(normalize(a), normalize(b))
}

/** Prostokąt twarzy w pikselach klatki. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun iou(o: Box): Float {
        val iw = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0f)
        val ih = (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (right - left) * (bottom - top) + (o.right - o.left) * (o.bottom - o.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * Jedna twarz do grupowania. [frameIndex] = numer próbkowanej klatki (kolejne próbki różnią się o 1);
 * służy do zakazu łączenia twarzy z tej samej klatki i do śledzenia między sąsiednimi klatkami ([box]).
 */
data class FaceSample(val id: Int, val frameIndex: Int, val embedding: FloatArray, val box: Box? = null)

data class ClusteringParams(
    /**
     * Próg średniego podobieństwa kosinusowego między grupami, powyżej którego grupy są scalane
     * (średnie powiązanie / average linkage). Główny parametr do strojenia.
     */
    val similarityThreshold: Float = 0.40f,
    /**
     * Próg pierwszego, „ostrożnego” etapu (przypisanie twarzy do najbliższej grupy).
     * Domyślnie [similarityThreshold] + 0.12 — tworzy dużo małych, czystych grup.
     */
    val seedThreshold: Float? = null,
    /** Grupy z mniejszą liczbą twarzy są odrzucane jako szum. */
    val minOccurrences: Int = 3,
    /**
     * Ile wspólnych klatek tolerujemy przy scalaniu (pomyłki detektora, odbicia w lustrze).
     * Dwie twarze w tej samej klatce to zwykle dwie różne osoby.
     */
    val maxSharedFrames: Int = 0,
    /**
     * Śledzenie: twarze z sąsiednich próbek (co 0,5 s) w tym samym miejscu kadru (IoU > [trackMinIou])
     * i o podobieństwie > [trackLinkThreshold] są łączone od razu, zanim zadziała próg główny.
     * Na nagraniach testowych pary tej samej osoby miały prawie zawsze > 0.3, różnych osób < 0.26.
     * null = bez śledzenia.
     */
    val trackLinkThreshold: Float? = 0.35f,
    val trackMinIou: Float = 0.3f,
)

data class FaceCluster(val memberIds: List<Int>) {
    val size get() = memberIds.size
}

data class ClusteringResult(
    /** Grupy z co najmniej minOccurrences twarzami, od największej. */
    val clusters: List<FaceCluster>,
    /** Grupy odrzucone jako szum (do podglądu w debug). */
    val rejected: List<FaceCluster>,
)

/**
 * Grupowanie twarzy w osoby. Trzy etapy:
 *
 * 0. Śledzenie: łączenie twarzy z sąsiednich próbek w tym samym miejscu kadru (patrz
 *    [ClusteringParams.trackLinkThreshold]) w „ścieżki”. Zmiana pozy/oświetlenia w obrębie
 *    jednego ujęcia kamery nie rozbija wtedy osoby na kilka kart.
 * 1. Przypisanie zachłanne (leader clustering) z wysokim progiem: każda twarz trafia do najbliższej
 *    grupy (po centroidzie), jeśli podobieństwo > seedThreshold i grupa nie ma już twarzy z tej
 *    samej klatki; w przeciwnym razie zakłada nową grupę. Daje to wiele małych, „czystych” grup.
 * 2. Scalanie aglomeracyjne ze średnim powiązaniem: łączymy parę grup o największym średnim
 *    podobieństwie par twarzy, dopóki przekracza ono similarityThreshold. Dla wektorów
 *    znormalizowanych średnie podobieństwo par = (S_a · S_b) / (n_a · n_b), gdzie S to suma
 *    wektorów grupy, więc liczymy je w O(wymiar).
 *
 * Czysty Kotlin — testowany na zapisanych embeddingach (src/test/resources).
 */
class FaceClusterer(private val params: ClusteringParams = ClusteringParams()) {

    private class Group(val dim: Int) {
        val members = mutableListOf<Int>()
        val frames = HashSet<Int>()
        val sum = FloatArray(dim)
        var alive = true

        fun add(id: Int, frame: Int, e: FloatArray) {
            members += id
            frames += frame
            for (i in sum.indices) sum[i] += e[i]
        }

        fun absorb(o: Group) {
            members += o.members
            frames += o.frames
            for (i in sum.indices) sum[i] += o.sum[i]
            o.alive = false
        }

        fun centroidSim(e: FloatArray): Float {
            var dot = 0f
            var norm = 0f
            for (i in sum.indices) {
                dot += sum[i] * e[i]
                norm += sum[i] * sum[i]
            }
            return if (norm <= 0f) 0f else dot / sqrt(norm)
        }

        fun avgLinkage(o: Group): Float = VectorMath.dot(sum, o.sum) / (members.size.toFloat() * o.members.size)

        fun sharedFrames(o: Group): Int {
            val (small, big) = if (frames.size <= o.frames.size) frames to o.frames else o.frames to frames
            return small.count { it in big }
        }
    }

    fun cluster(faces: List<FaceSample>): ClusteringResult {
        if (faces.isEmpty()) return ClusteringResult(emptyList(), emptyList())
        val dim = faces.first().embedding.size
        val seedThr = params.seedThreshold ?: (params.similarityThreshold + 0.12f)
        val normalized = faces.associate { it.id to VectorMath.normalize(it.embedding) }

        // --- Etap 0: ścieżki (śledzenie między sąsiednimi próbkami) ---
        val tracks = buildTracks(faces, normalized)

        // --- Etap 1: przypisanie zachłanne ścieżek (w kolejności czasu) ---
        val groups = mutableListOf<Group>()
        for (track in tracks) {
            val tg = Group(dim).apply { track.forEach { add(it.id, it.frameIndex, normalized.getValue(it.id)) } }
            var best: Group? = null
            var bestSim = seedThr
            for (g in groups) {
                if (g.sharedFrames(tg) > 0) continue
                val s = g.centroidSim(VectorMath.normalize(tg.sum))
                if (s > bestSim) {
                    bestSim = s; best = g
                }
            }
            if (best != null) best.absorb(tg) else groups += tg
        }

        // --- Etap 2: scalanie aglomeracyjne (średnie powiązanie) ---
        val n = groups.size
        val sim = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = groups[i].avgLinkage(groups[j])
            sim[i][j] = s; sim[j][i] = s
        }
        // Najlepszy sąsiad każdego wiersza — dzięki temu całość to ~O(n²), a nie O(n³).
        val rowBest = IntArray(n) { -1 }
        val rowBestSim = FloatArray(n) { Float.NEGATIVE_INFINITY }
        fun recomputeRow(i: Int) {
            rowBest[i] = -1
            rowBestSim[i] = Float.NEGATIVE_INFINITY
            val row = sim[i]
            for (j in 0 until n) {
                if (j == i || !groups[j].alive) continue
                if (row[j] > rowBestSim[i]) {
                    rowBestSim[i] = row[j]; rowBest[i] = j
                }
            }
        }
        for (i in 0 until n) recomputeRow(i)

        while (true) {
            var bi = -1
            var bs = params.similarityThreshold
            for (i in 0 until n) {
                if (groups[i].alive && rowBest[i] >= 0 && rowBestSim[i] > bs) {
                    bs = rowBestSim[i]; bi = i
                }
            }
            if (bi < 0) break
            val bj = rowBest[bi]
            // Zakaz łączenia osób widocznych jednocześnie w kadrze: blokujemy tę parę na stałe
            // (wspólnych klatek po kolejnych scaleniach może być tylko więcej).
            if (groups[bi].sharedFrames(groups[bj]) > params.maxSharedFrames) {
                sim[bi][bj] = Float.NEGATIVE_INFINITY
                sim[bj][bi] = Float.NEGATIVE_INFINITY
                recomputeRow(bi)
                if (rowBest[bj] == bi) recomputeRow(bj)
                continue
            }
            groups[bi].absorb(groups[bj])
            for (k in 0 until n) {
                if (k == bi || !groups[k].alive) continue
                // para zablokowana z którąkolwiek częścią zostaje zablokowana
                val blocked = sim[bi][k] == Float.NEGATIVE_INFINITY || sim[bj][k] == Float.NEGATIVE_INFINITY
                val s = if (blocked) Float.NEGATIVE_INFINITY else groups[bi].avgLinkage(groups[k])
                sim[bi][k] = s; sim[k][bi] = s
            }
            recomputeRow(bi)
            for (k in 0 until n) {
                if (k == bi || !groups[k].alive) continue
                if (rowBest[k] == bi || rowBest[k] == bj) recomputeRow(k)
                else if (sim[k][bi] > rowBestSim[k]) {
                    rowBestSim[k] = sim[k][bi]; rowBest[k] = bi
                }
            }
        }

        val all = groups.filter { it.alive }
            .map { FaceCluster(it.members.sorted()) }
            .sortedByDescending { it.size }
        return ClusteringResult(
            clusters = all.filter { it.size >= params.minOccurrences },
            rejected = all.filter { it.size < params.minOccurrences },
        )
    }

    /** Ścieżki twarzy posortowane wg pierwszej klatki; bez śledzenia — każda twarz osobno. */
    private fun buildTracks(faces: List<FaceSample>, normalized: Map<Int, FloatArray>): List<List<FaceSample>> {
        val sorted = faces.sortedWith(compareBy({ it.frameIndex }, { it.id }))
        val linkThr = params.trackLinkThreshold ?: return sorted.map { listOf(it) }
        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            return r
        }
        val byFrame = sorted.groupBy { it.frameIndex }
        for ((frame, cur) in byFrame) {
            val next = byFrame[frame + 1] ?: continue
            // kandydaci: nakładające się prostokąty i podobne embeddingi; dopasowanie 1:1 od najlepszej pary
            val cands = mutableListOf<Triple<FaceSample, FaceSample, Float>>()
            for (a in cur) for (b in next) {
                val ba = a.box ?: continue
                val bb = b.box ?: continue
                if (ba.iou(bb) < params.trackMinIou) continue
                val s = VectorMath.dot(normalized.getValue(a.id), normalized.getValue(b.id))
                if (s > linkThr) cands += Triple(a, b, s)
            }
            val usedA = HashSet<Int>()
            val usedB = HashSet<Int>()
            for ((a, b, _) in cands.sortedByDescending { it.third }) {
                if (a.id in usedA || b.id in usedB) continue
                usedA += a.id; usedB += b.id
                parent[find(b.id)] = find(a.id)
            }
        }
        return sorted.groupBy { find(it.id) }.values.sortedBy { it.first().frameIndex }
    }

    companion object {
        /** Średnie podobieństwo kosinusowe między dwiema grupami twarzy (do podglądu w debug). */
        fun averageSimilarity(a: List<FloatArray>, b: List<FloatArray>): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val dim = a.first().size
            val sa = FloatArray(dim)
            val sb = FloatArray(dim)
            a.forEach { v -> VectorMath.normalize(v).forEachIndexed { i, x -> sa[i] += x } }
            b.forEach { v -> VectorMath.normalize(v).forEachIndexed { i, x -> sb[i] += x } }
            return VectorMath.dot(sa, sb) / (a.size.toFloat() * b.size)
        }
    }
}
