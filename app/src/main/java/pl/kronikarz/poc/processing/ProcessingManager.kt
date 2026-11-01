package pl.kronikarz.poc.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kronikarz.poc.data.PersonOps
import pl.kronikarz.poc.data.ProcessingSettings
import pl.kronikarz.poc.data.Session
import pl.kronikarz.poc.data.SessionRepository
import pl.kronikarz.poc.data.SessionStatus
import java.util.UUID

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(val sessionId: String, val videoName: String, val progress: ProcessingProgress?) : ProcessingState
    data class Done(val sessionId: String) : ProcessingState
    data class Failed(val message: String) : ProcessingState
    data object Cancelled : ProcessingState
}

/**
 * Uruchamia przetwarzanie w korutynie o zasięgu aplikacji (przeżywa obrót ekranu i zmianę ekranu).
 * POC nie używa WorkManagera/usługi pierwszoplanowej, bo wymagałyby dodatkowych uprawnień
 * (powiadomienia, FOREGROUND_SERVICE). Na czas przetwarzania ekran jest podtrzymywany.
 */
class ProcessingManager(
    private val context: Context,
    private val repository: SessionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(uri: Uri, settings: ProcessingSettings) {
        if (job?.isActive == true) return
        val sessionId = UUID.randomUUID().toString()
        val name = RetrieverFrameSource.displayName(context, uri)
        _state.value = ProcessingState.Running(sessionId, name, null)
        job = scope.launch {
            try {
                val source = openSource(uri, settings)
                source.use {
                    if (it.info.durationMs <= 0) error("Nie udało się odczytać długości nagrania")
                    val session = Session(
                        id = sessionId,
                        createdAt = System.currentTimeMillis(),
                        video = it.info,
                        settings = settings,
                    )
                    repository.save(session)
                    val result = VideoProcessor(context, repository).process(session, it) { p ->
                        _state.value = ProcessingState.Running(sessionId, name, p)
                    }
                    repository.save(result)
                }
                _state.value = ProcessingState.Done(sessionId)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { repository.delete(sessionId) }
                _state.value = ProcessingState.Cancelled
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Przetwarzanie nie powiodło się", e)
                withContext(NonCancellable) { repository.delete(sessionId) }
                _state.value = ProcessingState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun openSource(uri: Uri, settings: ProcessingSettings): FrameSource {
        fun retriever() = RetrieverFrameSource(context, uri, settings.exactFrames, settings.maxFrameWidth)
        if (!settings.sequentialDecoder) return retriever()
        val base = retriever() // metadane (długość, wymiary, nazwa) bierzemy z retrievera
        val info = base.info
        base.close()
        val codec = runCatching { CodecFrameSource(context, uri, info, settings.maxFrameWidth) }
            .onFailure { Log.w(TAG, "MediaCodec niedostępny, używam MediaMetadataRetriever", it) }
            .getOrNull()
        return FallbackFrameSource(codec, ::retriever)
    }

    fun cancel() {
        job?.cancel()
    }

    /** Wynik obejrzany — wracamy do stanu spoczynku. */
    fun acknowledge() {
        if (_state.value !is ProcessingState.Running) _state.value = ProcessingState.Idle
    }

    /**
     * Debug: ponowne grupowanie zapisanych twarzy z nowymi ustawieniami (bez dekodowania wideo).
     * Dozwolone tylko, gdy sesja nie ma jeszcze nagrań — inaczej nagrania straciłyby osoby.
     */
    suspend fun regroup(sessionId: String, settings: ProcessingSettings): Result<Session> = withContext(Dispatchers.Default) {
        runCatching {
            val session = repository.get(sessionId) ?: error("Brak sesji")
            require(session.recordings.isEmpty()) { "Sesja ma już nagrania — ponowne grupowanie jest zablokowane" }
            val faces = repository.loadFaceRecords(sessionId) ?: error("Brak zapisanych twarzy (keepTuningData było wyłączone)")
            val built = PersonOps.buildPersons(faces, settings)
            val updated = session.copy(
                settings = session.settings.copy(
                    similarityThreshold = settings.similarityThreshold,
                    minOccurrences = settings.minOccurrences,
                ),
                persons = built.persons,
                rejectedGroups = built.rejected,
                status = SessionStatus.READY,
                lastPersonId = null,
            )
            repository.save(updated)
            updated
        }
    }

    private companion object {
        const val TAG = "ProcessingManager"
    }
}
