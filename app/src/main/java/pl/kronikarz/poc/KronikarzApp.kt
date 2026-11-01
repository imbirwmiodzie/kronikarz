package pl.kronikarz.poc

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.kronikarz.poc.audio.AudioPlayer
import pl.kronikarz.poc.data.SessionRepository
import pl.kronikarz.poc.data.SettingsStore
import pl.kronikarz.poc.export.SessionExporter
import pl.kronikarz.poc.processing.ProcessingManager
import pl.kronikarz.poc.questions.StaticQuestionProvider

/** Prosty kontener zależności (POC — bez Hilt/Koin). */
class KronikarzApp : Application() {
    lateinit var repository: SessionRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var processing: ProcessingManager
        private set
    lateinit var questions: StaticQuestionProvider
        private set
    lateinit var exporter: SessionExporter
        private set
    val player = AudioPlayer()

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(this)
        settings = SettingsStore(this)
        processing = ProcessingManager(this, repository)
        questions = StaticQuestionProvider.fromAssets(this)
        exporter = SessionExporter(this, repository)
        appScope.launch { repository.load() }
    }
}
