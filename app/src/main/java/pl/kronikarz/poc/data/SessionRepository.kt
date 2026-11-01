package pl.kronikarz.poc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Sesje trzymane jako pliki JSON w prywatnym katalogu aplikacji:
 *
 *     files/sessions/<id>/session.json   – sesja, osoby, nagrania
 *     files/sessions/<id>/faces/f_N.jpg    – wycinki twarzy
 *     files/sessions/<id>/audio/r_ID.m4a   – nagrania głosu
 *     files/sessions/<id>/faces.json     – embeddingi wszystkich twarzy (tylko gdy keepTuningData)
 *
 * Nic nie wychodzi poza urządzenie, dopóki użytkownik sam nie wyeksportuje sesji.
 */
class SessionRepository(context: Context) {

    private val root = File(context.filesDir, "sessions").apply { mkdirs() }
    private val mutex = Mutex()
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
                val f = File(dir, SESSION_FILE)
                runCatching { json.decodeFromString(Session.serializer(), f.readText()) }.getOrNull()
            }.associateBy { it.id }
            // Sesja „w trakcie”, której nikt już nie przetwarza (aplikację zamknięto) = przerwana.
            _sessions.value = loaded.mapValues { (_, s) ->
                if (s.status == SessionStatus.PROCESSING) s.copy(status = SessionStatus.FAILED, errorMessage = "Przetwarzanie przerwane")
                else s
            }
        }
    }

    fun dir(sessionId: String) = File(root, sessionId)
    fun file(sessionId: String, relativePath: String) = File(dir(sessionId), relativePath)

    fun get(sessionId: String): Session? = _sessions.value[sessionId]

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(session) }
    }

    /** Atomowa modyfikacja sesji. Zwraca nową wersję (albo null, jeśli sesji nie ma). */
    suspend fun update(sessionId: String, f: (Session) -> Session): Session? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _sessions.value[sessionId] ?: return@withLock null
            val updated = f(current)
            if (updated != current) writeLocked(updated)
            updated
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir(sessionId).deleteRecursively()
            _sessions.value = _sessions.value - sessionId
        }
    }

    suspend fun saveFaceRecords(sessionId: String, faces: List<FaceRecord>) = withContext(Dispatchers.IO) {
        val f = file(sessionId, FACES_FILE)
        val tmp = File(f.parentFile, "$FACES_FILE.tmp")
        tmp.writeText(compactJson.encodeToString(FaceRecordFile.serializer(), FaceRecordFile(faces)))
        tmp.renameTo(f)
    }

    suspend fun loadFaceRecords(sessionId: String): List<FaceRecord>? = withContext(Dispatchers.IO) {
        val f = file(sessionId, FACES_FILE)
        if (!f.exists()) null
        else runCatching { compactJson.decodeFromString(FaceRecordFile.serializer(), f.readText()).faces }.getOrNull()
    }

    private fun writeLocked(session: Session) {
        val d = dir(session.id).apply { mkdirs() }
        val tmp = File(d, "$SESSION_FILE.tmp")
        tmp.writeText(json.encodeToString(Session.serializer(), session))
        if (!tmp.renameTo(File(d, SESSION_FILE))) error("Nie udało się zapisać sesji")
        _sessions.value = _sessions.value + (session.id to session)
    }

    companion object {
        const val SESSION_FILE = "session.json"
        const val FACES_FILE = "faces.json"
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val compactJson = Json { ignoreUnknownKeys = true }
    }
}
