package pl.kronikarz.poc.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.kronikarz.poc.BuildConfig
import pl.kronikarz.poc.data.FaceShot
import pl.kronikarz.poc.data.PersonStatus
import pl.kronikarz.poc.data.ProcessingSettings
import pl.kronikarz.poc.data.ProcessingStats
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.SessionRepository
import pl.kronikarz.poc.data.VideoInfo
import pl.kronikarz.poc.questions.Question
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Format eksportu (wejście dalszego pipeline'u). Opisany w README, sekcja „Format eksportu”.
 * Zmiana niekompatybilna = podbicie [FORMAT_VERSION].
 */
@Serializable
data class ExportManifest(
    val format: String = "kronikarz-session",
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: String,
    val app: String,
    val session: ExportSession,
    val questions: List<Question>,
    val persons: List<ExportPerson>,
    val recordings: List<ExportRecording>,
)

@Serializable
data class ExportSession(
    val id: String,
    val createdAt: String,
    val video: VideoInfo,
    val settings: ProcessingSettings,
    val stats: ProcessingStats?,
)

@Serializable
data class ExportShot(val file: String, val timestampMs: Long, val quality: Float)

@Serializable
data class ExportPerson(
    val id: String,
    val number: Int,
    val name: String?,
    /** active | hidden | merged */
    val status: String,
    val mergedInto: String?,
    val occurrenceCount: Int,
    val timestampsMs: List<Long>,
    /** null dla osób ukrytych/scalonych (bez plików). */
    val bestShot: ExportShot?,
    val alternateShots: List<ExportShot>,
    val bestShotChosenByUser: Boolean,
    val recordingIds: List<String>,
)

@Serializable
data class ExportRecording(
    val id: String,
    val personId: String,
    val questionId: String,
    val questionText: String,
    val file: String,
    val durationMs: Long,
    val createdAt: String,
)

const val FORMAT_VERSION = 1

class SessionExporter(private val context: Context, private val repository: SessionRepository) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Tworzy ZIP w katalogu cache i zwraca plik. */
    suspend fun export(session: Session, questions: List<Question>): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() } // stare eksporty
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val zipFile = File(outDir, "kronikarz_${stamp}.zip")

        val (manifest, entries) = ExportBuilder.build(session, questions, System.currentTimeMillis(), "Kronikarz POC ${BuildConfig.VERSION_NAME}")
        val files = entries.map { (zipPath, relative) -> zipPath to repository.file(session.id, relative) }

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(json.encodeToString(ExportManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for ((path, src) in files) {
                if (!src.exists()) continue
                zip.putNextEntry(ZipEntry(path))
                src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zipFile
    }

    fun shareIntent(zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Eksportuj nagrania")
    }

    /** Zapis gotowego ZIP-a w miejscu wybranym przez użytkownika (SAF). */
    suspend fun copyTo(zip: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
            ?: error("Nie można zapisać pliku")
    }

}

/** Budowa manifestu eksportu — czysta funkcja (testowana na JVM). */
object ExportBuilder {
    /**
     * @return manifest oraz lista plików do ZIP-a: (ścieżka w ZIP, ścieżka względna w katalogu sesji).
     */
    fun build(session: Session, questions: List<Question>, nowMs: Long, appName: String): Pair<ExportManifest, List<Pair<String, String>>> {
        val files = mutableListOf<Pair<String, String>>()
        val persons = session.persons.sortedBy { it.number }.map { p ->
            val tag = "p%02d".format(p.number)
            val active = p.status == PersonStatus.ACTIVE
            fun shot(name: String, s: FaceShot): ExportShot {
                val path = "faces/${tag}_$name.jpg"
                files += path to s.cropPath
                return ExportShot(path, s.timestampMs, s.quality)
            }
            ExportPerson(
                id = p.id,
                number = p.number,
                name = p.name?.takeIf { it.isNotBlank() },
                status = p.status.name.lowercase(),
                mergedInto = p.mergedInto,
                occurrenceCount = p.occurrenceCount,
                timestampsMs = p.timestampsMs,
                bestShot = if (active) shot("best", p.bestShot) else null,
                alternateShots = if (active) p.alternateShots.mapIndexed { i, s -> shot("alt${i + 1}", s) } else emptyList(),
                bestShotChosenByUser = p.bestShotChosenByUser,
                recordingIds = session.recordingsOf(p.id).map { it.id },
            )
        }
        val numberOf = session.persons.associate { it.id to it.number }
        val counters = HashMap<String, Int>()
        val recordings = session.recordings.sortedBy { it.createdAt }.map { r ->
            val tag = "p%02d_%s".format(numberOf[r.personId] ?: 0, r.questionId)
            val n = (counters[tag] ?: 0) + 1
            counters[tag] = n
            val path = "audio/${tag}_$n.m4a"
            files += path to r.audioPath
            ExportRecording(r.id, r.personId, r.questionId, r.questionText, path, r.durationMs, iso(r.createdAt))
        }
        val manifest = ExportManifest(
            exportedAt = iso(nowMs),
            app = appName,
            session = ExportSession(session.id, iso(session.createdAt), session.video, session.settings, session.stats),
            questions = questions,
            persons = persons,
            recordings = recordings,
        )
        return manifest to files
    }

    fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
