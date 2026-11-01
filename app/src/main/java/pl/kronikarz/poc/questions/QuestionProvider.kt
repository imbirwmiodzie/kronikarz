package pl.kronikarz.poc.questions

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.Recording
import pl.kronikarz.poc.data.Session

@Serializable
data class Question(val id: String, val text: String)

@Serializable
data class QuestionConfig(val version: Int = 1, val questions: List<Question>, val closing: Question)

/** Wszystko, co dostawca pytań może wiedzieć o osobie, żeby wybrać kolejne pytanie. */
data class QuestionContext(
    val session: Session,
    val person: Person,
    val recordings: List<Recording>,
)

data class QuestionStep(
    val question: Question,
    /** Numer pytania (1-based) w puli albo null dla pytania zamykającego / wygenerowanego. */
    val index: Int?,
    val total: Int,
    /** true, gdy wszystkie pytania z listy już omówiono (lub pominięto). */
    val isClosing: Boolean,
)

/**
 * Źródło pytań pomocniczych. POC: stała lista z assets/questions.json ([StaticQuestionProvider]).
 * Docelowo: implementacja generująca pytania przez AI na podstawie tego, czego brakuje
 * (np. po transkrypcji nagrań) — stąd `suspend` i pełny kontekst osoby.
 */
interface QuestionProvider {
    suspend fun current(ctx: QuestionContext): QuestionStep

    /** Treść pytania po id (np. do opisu starszego nagrania). */
    fun textOf(questionId: String): String?
}

class StaticQuestionProvider(private val config: QuestionConfig) : QuestionProvider {

    override suspend fun current(ctx: QuestionContext): QuestionStep {
        val answered = ctx.recordings.map { it.questionId }.toSet()
        val skipped = ctx.person.skippedQuestionIds.toSet()
        val total = config.questions.size
        val idx = config.questions.indexOfFirst { it.id !in answered && it.id !in skipped }
        return if (idx >= 0) QuestionStep(config.questions[idx], idx + 1, total, isClosing = false)
        else QuestionStep(config.closing, null, total, isClosing = true)
    }

    /** Czy są jeszcze pominięte, nieomówione pytania (do „Następne pytanie” na pytaniu zamykającym). */
    fun hasUnansweredSkipped(ctx: QuestionContext): Boolean {
        val answered = ctx.recordings.map { it.questionId }.toSet()
        return ctx.person.skippedQuestionIds.any { it !in answered }
    }

    override fun textOf(questionId: String): String? =
        (config.questions + config.closing).firstOrNull { it.id == questionId }?.text

    val questions: List<Question> get() = config.questions

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromAssets(context: Context, path: String = "questions.json"): StaticQuestionProvider {
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            return StaticQuestionProvider(json.decodeFromString(QuestionConfig.serializer(), text))
        }
    }
}
