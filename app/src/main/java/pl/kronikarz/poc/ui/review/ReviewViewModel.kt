package pl.kronikarz.poc.ui.review

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.kronikarz.poc.KronikarzApp
import pl.kronikarz.poc.audio.VoiceRecorder
import pl.kronikarz.poc.data.Person
import pl.kronikarz.poc.data.PersonOps
import pl.kronikarz.poc.data.Recording
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.questions.QuestionContext
import pl.kronikarz.poc.questions.QuestionStep
import java.util.UUID

data class ReviewUi(
    val session: Session,
    val persons: List<Person>,
    val index: Int,
    val person: Person,
    val recordings: List<Recording>,
    val question: QuestionStep?,
)

data class RecorderUi(val recording: Boolean = false, val elapsedMs: Long = 0, val level: Float = 0f)

/** Komunikat z możliwością cofnięcia (ukrycie, scalenie). */
data class UndoMessage(val text: String, val snapshot: Session, val restorePersonId: String)

class ReviewViewModel(
    private val app: KronikarzApp,
    private val sessionId: String,
    initialPersonId: String?,
) : ViewModel() {

    private val repo = app.repository
    private val selectedId = MutableStateFlow(initialPersonId)
    /** Pozycja na liście — gdy bieżąca osoba zniknie (ukryta/scalona), pokazujemy następną. */
    private var lastIndex = 0

    private val _recorder = MutableStateFlow(RecorderUi())
    val recorder: StateFlow<RecorderUi> = _recorder.asStateFlow()
    private val voice = VoiceRecorder(app)
    private var recordingFor: Pair<String, QuestionStep>? = null
    private var tickerJob: Job? = null

    private val _undo = MutableStateFlow<UndoMessage?>(null)
    val undo: StateFlow<UndoMessage?> = _undo.asStateFlow()

    private val _finished = MutableStateFlow(false)
    /** true, gdy użytkownik przeszedł za ostatnią osobę. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    val playingId = app.player.playingId

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<ReviewUi?> = combine(repo.sessions.map { it[sessionId] }, selectedId) { s, sel -> s to sel }
        .mapLatest { (s, sel) ->
            if (s == null) return@mapLatest null
            val persons = s.activePersons
            if (persons.isEmpty()) return@mapLatest null
            var idx = persons.indexOfFirst { it.id == (sel ?: s.lastPersonId) }
            if (idx < 0) idx = lastIndex.coerceIn(0, persons.size - 1)
            lastIndex = idx
            val person = persons[idx]
            if (sel != person.id) selectedId.value = person.id
            val recs = s.recordingsOf(person.id)
            val q = app.questions.current(QuestionContext(s, person, recs))
            ReviewUi(s, persons, idx, person, recs, q)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- nawigacja ---

    fun goTo(personId: String) {
        stopRecordingIfAny()
        selectedId.value = personId
        persistPosition(personId)
    }

    fun nextPerson() {
        val u = ui.value ?: return
        stopRecordingIfAny()
        if (u.index + 1 >= u.persons.size) {
            _finished.value = true
            return
        }
        goTo(u.persons[u.index + 1].id)
    }

    fun previousPerson() {
        val u = ui.value ?: return
        if (u.index > 0) goTo(u.persons[u.index - 1].id)
    }

    fun consumeFinished() {
        _finished.value = false
    }

    private fun persistPosition(personId: String) = viewModelScope.launch {
        repo.update(sessionId) { it.copy(lastPersonId = personId) }
    }

    // --- pytania ---

    fun nextQuestion() {
        val u = ui.value ?: return
        val q = u.question ?: return
        viewModelScope.launch {
            repo.update(sessionId) { s ->
                if (q.isClosing) PersonOps.clearSkipped(s, u.person.id) // wróć do pominiętych
                else PersonOps.skipQuestion(s, u.person.id, q.question.id)
            }
        }
    }

    // --- nagrywanie ---

    fun startRecording() {
        val u = ui.value ?: return
        val q = u.question ?: return
        if (voice.isRecording) return
        val file = repo.file(sessionId, "audio/r_${UUID.randomUUID()}.m4a")
        try {
            voice.start(file)
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się rozpocząć nagrywania", e)
            Toast.makeText(app, "Nie udało się włączyć mikrofonu", Toast.LENGTH_LONG).show()
            return
        }
        app.player.stop()
        recordingFor = u.person.id to q
        pendingFile = file
        _recorder.value = RecorderUi(recording = true)
        tickerJob = viewModelScope.launch {
            while (isActive && voice.isRecording) {
                _recorder.value = RecorderUi(true, voice.elapsedMs, voice.level())
                delay(80)
            }
        }
    }

    private var pendingFile: java.io.File? = null

    fun stopRecording() {
        val target = recordingFor ?: return
        val file = pendingFile ?: return
        tickerJob?.cancel()
        val duration = voice.stop()
        recordingFor = null
        pendingFile = null
        _recorder.value = RecorderUi()
        if (duration == null) {
            Toast.makeText(app, "Nagranie było za krótkie — spróbuj jeszcze raz", Toast.LENGTH_SHORT).show()
            return
        }
        val rec = Recording(
            id = UUID.randomUUID().toString(),
            personId = target.first,
            questionId = target.second.question.id,
            questionText = target.second.question.text,
            audioPath = "audio/${file.name}",
            durationMs = duration,
            createdAt = System.currentTimeMillis(),
        )
        // appScope: zapis ma się udać nawet, gdy ekran jest właśnie zamykany
        app.appScope.launch { repo.update(sessionId) { it.copy(recordings = it.recordings + rec) } }
    }

    private fun stopRecordingIfAny() {
        if (voice.isRecording) stopRecording()
    }

    fun togglePlay(rec: Recording) = app.player.toggle(rec.id, repo.file(sessionId, rec.audioPath))

    fun deleteRecording(rec: Recording) {
        if (app.player.playingId.value == rec.id) app.player.stop()
        viewModelScope.launch {
            repo.update(sessionId) { s -> s.copy(recordings = s.recordings.filterNot { it.id == rec.id }) }
            repo.file(sessionId, rec.audioPath).delete()
        }
    }

    // --- edycja osób ---

    private var nameJob: Job? = null
    private var pendingName: Pair<String, String>? = null

    fun setName(personId: String, name: String) {
        nameJob?.cancel()
        pendingName = personId to name
        nameJob = viewModelScope.launch {
            delay(400)
            flushName()
        }
    }

    private fun flushName() {
        val (personId, name) = pendingName ?: return
        pendingName = null
        app.appScope.launch { repo.update(sessionId) { PersonOps.setName(it, personId, name) } }
    }

    fun chooseShot(faceId: Int) {
        val u = ui.value ?: return
        viewModelScope.launch { repo.update(sessionId) { PersonOps.chooseBestShot(it, u.person.id, faceId) } }
    }

    fun hideCurrent() {
        val u = ui.value ?: return
        stopRecordingIfAny()
        viewModelScope.launch {
            repo.update(sessionId) { PersonOps.hide(it, u.person.id) }
            _undo.value = UndoMessage("Ukryto osobę ${u.person.number}", u.session, u.person.id)
            afterRemoval(u)
        }
    }

    fun mergeCurrentInto(targetId: String) {
        val u = ui.value ?: return
        stopRecordingIfAny()
        viewModelScope.launch {
            repo.update(sessionId) { PersonOps.merge(it, u.person.id, targetId) }
            val target = u.persons.first { it.id == targetId }
            _undo.value = UndoMessage("Połączono z: ${target.name?.takeIf { it.isNotBlank() } ?: "Osoba ${target.number}"}", u.session, u.person.id)
            afterRemoval(u)
        }
    }

    private fun afterRemoval(before: ReviewUi) {
        // pokazujemy osobę, która była następna; po ostatniej — poprzednią
        val remaining = before.persons.filterNot { it.id == before.person.id }
        if (remaining.isEmpty()) {
            _finished.value = true
            return
        }
        val next = remaining.getOrNull(before.index) ?: remaining.last()
        selectedId.value = next.id
    }

    fun undoLast() {
        val u = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            // Cofnięcie = przywrócenie migawki sesji, ale z zachowaniem nagrań zrobionych w międzyczasie.
            repo.update(sessionId) { current ->
                val currentIds = current.recordings.map { it.id }.toSet()
                val kept = u.snapshot.recordings.filter { it.id in currentIds } // usunięte w międzyczasie nie wracają
                val newRecs = current.recordings.filter { r -> u.snapshot.recordings.none { it.id == r.id } }
                u.snapshot.copy(recordings = kept + newRecs, lastPersonId = u.restorePersonId)
            }
            selectedId.value = u.restorePersonId
        }
    }

    fun dismissUndo() {
        _undo.value = null
    }

    override fun onCleared() {
        stopRecordingIfAny()
        app.player.stop()
        nameJob?.cancel()
        flushName()
    }

    class Factory(private val app: KronikarzApp, private val sessionId: String, private val personId: String?) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewViewModel(app, sessionId, personId) as T
    }

    private companion object {
        const val TAG = "ReviewViewModel"
    }
}
