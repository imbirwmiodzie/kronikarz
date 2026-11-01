package pl.kronikarz.poc.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kronikarz.poc.data.FaceShot
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.PersonOps
import pl.kronikarz.poc.data.ProcessingSettings
import pl.kronikarz.poc.data.Recording
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.SessionStatus
import pl.kronikarz.poc.data.VideoInfo
import pl.kronikarz.poc.questions.Question

class ExportBuilderTest {
    private fun shot(id: Int) = FaceShot(id, "faces/f_$id.jpg", id * 1000L, 0.5f)
    private fun person(id: String, n: Int, name: String? = null) =
        Person(id, n, shot(n * 10), listOf(shot(n * 10 + 1), shot(n * 10 + 2)), 5, listOf(1, 2, 3, 4, 5), name = name)

    private val base = Session(
        id = "s1",
        createdAt = 0,
        video = VideoInfo("content://v", "wesele.mp4", 60_000, 720, 576),
        settings = ProcessingSettings(),
        status = SessionStatus.READY,
        persons = listOf(person("a", 1, "Babcia"), person("b", 2), person("c", 3)),
        recordings = listOf(
            Recording("r1", "a", "who", "Kim jest ta osoba? Jak się nazywa?", "audio/r_1.m4a", 4000, 10),
            Recording("r2", "a", "more", "Czy chcesz dodać coś jeszcze?", "audio/r_2.m4a", 3000, 20),
            Recording("r3", "a", "more", "Czy chcesz dodać coś jeszcze?", "audio/r_3.m4a", 2000, 30),
            Recording("r4", "b", "who", "Kim jest ta osoba? Jak się nazywa?", "audio/r_4.m4a", 1000, 40),
        ),
    )
    private val questions = listOf(Question("who", "Kim jest ta osoba? Jak się nazywa?"))

    @Test
    fun `pliki i nazwy w ZIP`() {
        val session = PersonOps.hide(PersonOps.merge(base, "b", "a"), "c")
        val (m, files) = ExportBuilder.build(session, questions, 86_400_000, "test")
        val paths = files.map { it.first }
        // tylko aktywna osoba ma zdjęcia: najlepsze + alternatywy (w tym przejęte ze scalonej)
        assertEquals(listOf("faces/p01_best.jpg", "faces/p01_alt1.jpg", "faces/p01_alt2.jpg", "faces/p01_alt3.jpg", "faces/p01_alt4.jpg", "faces/p01_alt5.jpg"),
            paths.filter { it.startsWith("faces/") })
        // nagrania: numeracja w obrębie (osoba, pytanie); r4 przeszło do osoby 1 po scaleniu
        assertEquals(listOf("audio/p01_who_1.m4a", "audio/p01_more_1.m4a", "audio/p01_more_2.m4a", "audio/p01_who_2.m4a"),
            paths.filter { it.startsWith("audio/") })
        assertEquals(paths.size, paths.toSet().size)
        assertEquals("audio/r_4.m4a", files.first { it.first == "audio/p01_who_2.m4a" }.second)

        val b = m.persons.first { it.id == "b" }
        assertEquals("merged", b.status)
        assertEquals("a", b.mergedInto)
        assertNull(b.bestShot)
        assertEquals("hidden", m.persons.first { it.id == "c" }.status)
        assertEquals(listOf("r1", "r2", "r3", "r4"), m.persons.first { it.id == "a" }.recordingIds)
        assertEquals("1970-01-02T00:00:00Z", m.exportedAt)
    }

    @Test
    fun `session json ma ustalony ksztalt formatVersion 1`() {
        val (m, _) = ExportBuilder.build(base, questions, 0, "test")
        val json = Json { encodeDefaults = true }
        val root = Json.parseToJsonElement(json.encodeToString(ExportManifest.serializer(), m)).jsonObject
        assertEquals("kronikarz-session", root["format"]!!.jsonPrimitive.content)
        assertEquals(1, root["formatVersion"]!!.jsonPrimitive.content.toInt())
        assertTrue(root.keys.containsAll(listOf("exportedAt", "app", "session", "questions", "persons", "recordings")))
        val p = root["persons"]!!.jsonArray[0].jsonObject
        assertTrue(p.keys.containsAll(listOf("id", "number", "name", "status", "mergedInto", "occurrenceCount", "timestampsMs", "bestShot", "alternateShots", "recordingIds")))
        val r = root["recordings"]!!.jsonArray[0].jsonObject
        assertTrue(r.keys.containsAll(listOf("id", "personId", "questionId", "questionText", "file", "durationMs", "createdAt")))
        assertEquals("Babcia", p["name"]!!.jsonPrimitive.content)
    }
}
