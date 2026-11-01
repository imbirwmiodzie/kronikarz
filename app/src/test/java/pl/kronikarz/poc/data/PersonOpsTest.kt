package pl.kronikarz.poc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonOpsTest {
    private fun shot(id: Int, t: Long, q: Float) = FaceShot(id, "faces/f_$id.jpg", t, q)

    private fun person(id: String, n: Int, best: FaceShot, alts: List<FaceShot>, count: Int, name: String? = null) =
        Person(id, n, best, alts, count, (0 until count).map { it * 1000L + n }, name = name, faceIds = listOf(best.faceId) + alts.map { it.faceId })

    private val session = Session(
        id = "s",
        createdAt = 0,
        video = VideoInfo("content://x", "x.mp4", 60_000, 720, 576),
        settings = ProcessingSettings(),
        status = SessionStatus.READY,
        persons = listOf(
            person("a", 1, shot(1, 1000, 0.9f), listOf(shot(2, 9000, 0.7f)), 10, name = "Babcia"),
            person("b", 2, shot(3, 2000, 0.8f), listOf(shot(4, 8000, 0.6f)), 5),
            person("c", 3, shot(5, 3000, 0.5f), emptyList(), 3),
        ),
        recordings = listOf(
            Recording("r1", "b", "who", "Kim jest…", "audio/r1.m4a", 3000, 1),
            Recording("r2", "a", "who", "Kim jest…", "audio/r2.m4a", 3000, 2),
        ),
    )

    @Test
    fun `scalenie przenosi wystapienia, ujecia i nagrania`() {
        val s = PersonOps.merge(session, sourceId = "b", targetId = "a")
        val a = s.persons.first { it.id == "a" }
        val b = s.persons.first { it.id == "b" }
        assertEquals(PersonStatus.MERGED, b.status)
        assertEquals("a", b.mergedInto)
        assertEquals(15, a.occurrenceCount)
        assertEquals(15, a.timestampsMs.size)
        assertEquals(a.timestampsMs.sorted(), a.timestampsMs)
        assertEquals(listOf(2, 3, 4), a.alternateShots.map { it.faceId })
        assertEquals(1, a.bestShot.faceId) // główne zdjęcie celu bez zmian
        assertEquals("Babcia", a.name)
        assertTrue(s.recordings.all { it.personId == "a" })
        assertEquals(listOf("a", "c"), s.activePersons.map { it.id })
    }

    @Test
    fun `scalenie bez imienia w celu przejmuje imie zrodla`() {
        val s = PersonOps.merge(session, sourceId = "a", targetId = "c")
        assertEquals("Babcia", s.persons.first { it.id == "c" }.name)
    }

    @Test
    fun `lancuch scalen wskazuje ostateczny cel`() {
        val s1 = PersonOps.merge(session, "c", "b")
        val s2 = PersonOps.merge(s1, "b", "a")
        assertEquals("a", s2.persons.first { it.id == "c" }.mergedInto)
        assertEquals(18, s2.persons.first { it.id == "a" }.occurrenceCount)
    }

    @Test
    fun `ukrycie i przywrocenie`() {
        val hidden = PersonOps.hide(session, "c")
        assertEquals(listOf("a", "b"), hidden.activePersons.map { it.id })
        assertEquals(3, PersonOps.hide(hidden, "c", hidden = false).activePersons.size)
    }

    @Test
    fun `wybor alternatywnego ujecia zamienia je z glownym`() {
        val s = PersonOps.chooseBestShot(session, "a", 2)
        val a = s.persons.first { it.id == "a" }
        assertEquals(2, a.bestShot.faceId)
        assertEquals(listOf(1), a.alternateShots.map { it.faceId })
        assertTrue(a.bestShotChosenByUser)
        // nieistniejące ujęcie = bez zmian
        assertEquals(session.persons[0], PersonOps.chooseBestShot(session, "a", 999).persons[0])
    }

    @Test
    fun `kolejnosc przegladania = najczesciej widoczni najpierw`() {
        assertEquals(listOf("a", "b", "c"), session.activePersons.map { it.id })
        assertNull(session.lastPersonId)
    }

    @Test
    fun `budowanie osob z twarzy`() {
        fun emb(dir: Int) = FloatArray(8) { if (it == dir) 1f else 0.05f }
        val q = pl.kronikarz.poc.analysis.QualityBreakdown(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val faces = (0 until 12).map { i ->
            val person = i % 3 // 0,1,2 — po 4 twarze; osoba 2 tylko raz w innym kierunku
            FaceRecord(i, i, i * 5_000L, 0, 0, 10, 10, 0f, 0f, 0f, q.copy(total = i / 12f), "faces/f_$i.jpg", emb(person))
        } + FaceRecord(99, 99, 999_000, 0, 0, 10, 10, 0f, 0f, 0f, q, "faces/f_99.jpg", emb(7))
        val built = PersonOps.buildPersons(faces, ProcessingSettings(similarityThreshold = 0.5f, minOccurrences = 3))
        assertEquals(3, built.persons.size)
        assertEquals(listOf(4, 4, 4), built.persons.map { it.occurrenceCount })
        assertEquals(1, built.rejected.size)
        // najlepsze = najwyższa jakość w grupie
        built.persons.forEach { p -> assertEquals(p.faceIds.max(), p.bestShot.faceId) }
        assertTrue(built.usedFaceIds.containsAll(built.persons.map { it.bestShot.faceId }))
    }
}
