package pl.kronikarz.poc.analysis

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Narzędzie (nie test regresyjny): grupuje twarze z dowolnego faces.json (tools/extract_faces.py)
 * i wypisuje metryki dla zakresu progów. Uruchamiane tylko z parametrem -PevalFaces=…
 *
 *   ./gradlew testDebugUnitTest --tests '*ClusteringEvalTool*' -PevalFaces=/abs/faces.json [-PevalLabels=/abs/labels.json] [-PevalOut=/abs/clusters.json]
 *
 * evalOut: zapisuje grupy dla progu domyślnego (do obejrzenia w tools/contact_sheets.py).
 */
class ClusteringEvalTool {
    @Test
    fun run() {
        val path = System.getProperty("evalFaces").orEmpty()
        assumeTrue("brak -PevalFaces", path.isNotBlank())
        val faces = ClusteringEval.json.decodeFromString(EvalFile.serializer(), File(path).readText()).faces
        println("Twarzy: ${faces.size}")
        val labelsPath = System.getProperty("evalLabels").orEmpty()
        if (labelsPath.isNotBlank()) {
            val labels = ClusteringEval.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), File(labelsPath).readText())
                .mapKeys { it.key.toInt() }
            for (tracking in listOf(false, true)) {
                println(if (tracking) "--- ze śledzeniem" else "--- bez śledzenia")
                for (t in listOf(0.25f, 0.30f, 0.33f, 0.36f, 0.38f, 0.40f, 0.44f, 0.48f, 0.52f)) {
                    val m = ClusteringEval.evaluate(faces, labels, t, tracking = tracking)
                    println(m)
                    if (System.getProperty("evalVerbose") == "1") println("    " + m.cardSizes.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${it.value}" })
                }
            }
        }
        val out = System.getProperty("evalOut").orEmpty()
        if (out.isNotBlank()) {
            val thr = System.getProperty("evalThreshold")?.toFloatOrNull() ?: ClusteringParams().similarityThreshold
            val start = System.nanoTime()
            val r = FaceClusterer(ClusteringParams(similarityThreshold = thr)).cluster(faces.map { it.sample() })
            println("Grupowanie: %d ms, grup %d, odrzuconych %d".format((System.nanoTime() - start) / 1_000_000, r.clusters.size, r.rejected.size))
            File(out).writeText(
                "{\"clusters\":[" + r.clusters.joinToString(",") { c -> c.memberIds.joinToString(",", "[", "]") } + "]," +
                    "\"rejected\":[" + r.rejected.joinToString(",") { c -> c.memberIds.joinToString(",", "[", "]") } + "]}"
            )
        }
    }
}
