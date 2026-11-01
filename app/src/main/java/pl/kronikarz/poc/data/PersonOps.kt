package pl.kronikarz.poc.data

import pl.kronikarz.poc.analysis.Box
import pl.kronikarz.poc.analysis.ClusteringParams
import pl.kronikarz.poc.analysis.FaceClusterer
import pl.kronikarz.poc.analysis.FaceSample
import pl.kronikarz.poc.analysis.ScoredShot
import pl.kronikarz.poc.analysis.ShotSelector
import pl.kronikarz.poc.analysis.VectorMath
import java.util.UUID

/** Operacje na osobach sesji — czyste funkcje (łatwe do testowania i cofania). */
object PersonOps {

    data class BuildResult(val persons: List<Person>, val rejected: List<RejectedGroup>, val usedFaceIds: Set<Int>)

    /** Grupuje twarze i buduje karty osób (najlepsze ujęcie + alternatywy). */
    fun buildPersons(faces: List<FaceRecord>, settings: ProcessingSettings): BuildResult {
        val byId = faces.associateBy { it.id }
        val result = FaceClusterer(
            ClusteringParams(
                similarityThreshold = settings.similarityThreshold,
                minOccurrences = settings.minOccurrences,
            )
        ).cluster(faces.map { FaceSample(it.id, it.frameIndex, it.embedding, Box(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())) })

        val used = HashSet<Int>()
        val persons = result.clusters.mapIndexed { idx, cluster ->
            val members = cluster.memberIds.map { byId.getValue(it) }
            val sel = ShotSelector.select(members.map { ScoredShot(it.id, it.timestampMs, it.quality.total) })
            fun shot(s: ScoredShot) = byId.getValue(s.faceId).let { FaceShot(it.id, it.cropPath, it.timestampMs, it.quality.total) }
            used += sel.best.faceId
            sel.alternates.forEach { used += it.faceId }
            Person(
                id = UUID.randomUUID().toString(),
                number = idx + 1,
                bestShot = shot(sel.best),
                alternateShots = sel.alternates.map(::shot),
                occurrenceCount = members.size,
                timestampsMs = members.map { it.timestampMs }.sorted(),
                faceIds = cluster.memberIds,
                centroid = meanEmbedding(members.map { it.embedding }),
            )
        }
        return BuildResult(persons, result.rejected.map { RejectedGroup(it.memberIds) }, used)
    }

    fun hide(session: Session, personId: String, hidden: Boolean = true): Session =
        session.updatePerson(personId) { it.copy(status = if (hidden) PersonStatus.HIDDEN else PersonStatus.ACTIVE) }

    /** Ustawia jedno z alternatywnych ujęć jako główne; dotychczasowe główne przechodzi do alternatyw. */
    fun chooseBestShot(session: Session, personId: String, faceId: Int): Session =
        session.updatePerson(personId) { p ->
            val alt = p.alternateShots.firstOrNull { it.faceId == faceId } ?: return@updatePerson p
            p.copy(
                bestShot = alt,
                alternateShots = p.alternateShots.map { if (it.faceId == faceId) p.bestShot else it },
                bestShotChosenByUser = true,
            )
        }

    /**
     * „To ta sama osoba co…”: [sourceId] zostaje scalona z [targetId]. Nagrania i wystąpienia
     * przechodzą do osoby docelowej, najlepsze ujęcie źródła trafia do jej alternatyw.
     */
    fun merge(session: Session, sourceId: String, targetId: String): Session {
        require(sourceId != targetId)
        val source = session.persons.first { it.id == sourceId }
        val target = session.persons.first { it.id == targetId }
        val mergedTarget = target.copy(
            occurrenceCount = target.occurrenceCount + source.occurrenceCount,
            timestampsMs = (target.timestampsMs + source.timestampsMs).sorted(),
            alternateShots = (target.alternateShots + source.bestShot + source.alternateShots)
                .distinctBy { it.faceId }.take(MAX_ALTERNATES_AFTER_MERGE),
            name = target.name?.takeIf { it.isNotBlank() } ?: source.name,
            faceIds = (target.faceIds + source.faceIds).sorted(),
            centroid = weightedCentroid(target, source),
        )
        return session.copy(
            persons = session.persons.map {
                when (it.id) {
                    targetId -> mergedTarget
                    sourceId -> it.copy(status = PersonStatus.MERGED, mergedInto = targetId)
                    // osoby wcześniej scalone ze źródłem wskazują teraz na cel
                    else -> if (it.mergedInto == sourceId) it.copy(mergedInto = targetId) else it
                }
            },
            recordings = session.recordings.map { if (it.personId == sourceId) it.copy(personId = targetId) else it },
        )
    }

    fun setName(session: Session, personId: String, name: String): Session =
        session.updatePerson(personId) { it.copy(name = name) }

    fun skipQuestion(session: Session, personId: String, questionId: String): Session =
        session.updatePerson(personId) { it.copy(skippedQuestionIds = (it.skippedQuestionIds + questionId).distinct()) }

    fun clearSkipped(session: Session, personId: String): Session =
        session.updatePerson(personId) { it.copy(skippedQuestionIds = emptyList()) }

    /**
     * Pozostałe aktywne osoby posortowane od najbardziej podobnej do [personId]
     * (najczęstsze duplikaty trafiają na początek listy „To ta sama osoba co…”).
     */
    fun mergeCandidates(session: Session, personId: String): List<Pair<Person, Float?>> {
        val me = session.persons.first { it.id == personId }
        return session.activePersons.filter { it.id != personId }
            .map { other ->
                val sim = if (me.centroid != null && other.centroid != null) VectorMath.dot(me.centroid, other.centroid) else null
                other to sim
            }
            .sortedWith(compareByDescending<Pair<Person, Float?>> { it.second ?: -1f }.thenByDescending { it.first.occurrenceCount })
    }

    private fun meanEmbedding(es: List<FloatArray>): FloatArray? {
        if (es.isEmpty()) return null
        val sum = FloatArray(es.first().size)
        es.forEach { e -> VectorMath.normalize(e).forEachIndexed { i, v -> sum[i] += v } }
        return VectorMath.normalize(sum)
    }

    private fun weightedCentroid(a: Person, b: Person): FloatArray? {
        val ca = a.centroid ?: return b.centroid
        val cb = b.centroid ?: return ca
        return VectorMath.normalize(FloatArray(ca.size) { ca[it] * a.occurrenceCount + cb[it] * b.occurrenceCount })
    }

    private inline fun Session.updatePerson(id: String, f: (Person) -> Person): Session =
        copy(persons = persons.map { if (it.id == id) f(it) else it })

    const val MAX_ALTERNATES_AFTER_MERGE = 6
}
