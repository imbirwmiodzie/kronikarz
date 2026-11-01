package pl.kronikarz.poc.questions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kronikarz.poc.data.FaceShot
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.ProcessingSettings
import pl.kronikarz.poc.data.Recording
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.VideoInfo
import java.io.File

class StaticQuestionProviderTest {
    // ten sam plik, który trafia do aplikacji
    private val config = Json.decodeFromString(QuestionConfig.serializer(), File("src/main/assets/questions.json").readText())
    private val provider = StaticQuestionProvider(config)

    private val person = Person("p", 1, FaceShot(1, "f", 0, 1f), emptyList(), 3, listOf(0, 1, 2))
    private val session = Session("s", 0, VideoInfo("u", "n", 1, 1, 1), ProcessingSettings(), persons = listOf(person))

    private fun rec(q: String) = Recording(q + "_r", "p", q, q, "a", 1000, 0)
    private fun current(p: Person = person, recs: List<Recording> = emptyList()) =
        runBlocking { provider.current(QuestionContext(session, p, recs)) }

    @Test
    fun `szesc pytan z assets w kolejnosci`() {
        assertEquals(6, config.questions.size)
        assertEquals("Kim jest ta osoba? Jak się nazywa?", config.questions.first().text)
        assertEquals(config.questions.size, config.questions.map { it.id }.toSet().size)
    }

    @Test
    fun `po nagraniu przechodzi do kolejnego pytania, ktorego jeszcze nie ma`() {
        assertEquals("who", current().question.id)
        assertEquals(1, current().index)
        assertEquals("relation", current(recs = listOf(rec("who"))).question.id)
        // odpowiedź na pytanie 3 „poza kolejnością” nie przeskakuje pytania 2
        assertEquals("relation", current(recs = listOf(rec("who"), rec("age_year"))).question.id)
        assertEquals("place", current(recs = listOf(rec("who"), rec("relation"), rec("age_year"))).question.id)
    }

    @Test
    fun `pominiecie i pytanie zamykajace`() {
        val skipped = person.copy(skippedQuestionIds = listOf("who"))
        assertEquals("relation", current(skipped).question.id)
        val allSkipped = person.copy(skippedQuestionIds = config.questions.map { it.id })
        val step = current(allSkipped)
        assertTrue(step.isClosing)
        assertEquals("more", step.question.id)
        assertTrue(provider.hasUnansweredSkipped(QuestionContext(session, allSkipped, emptyList())))
        val allAnswered = config.questions.map { rec(it.id) }
        assertTrue(current(recs = allAnswered).isClosing)
        assertFalse(provider.hasUnansweredSkipped(QuestionContext(session, allSkipped, allAnswered)))
    }

    @Test
    fun `tresc pytania po id`() {
        assertEquals("Gdzie to nagranie mogło powstać?", provider.textOf("place"))
    }
}
