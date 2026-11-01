package pl.kronikarz.poc.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Ustawienia przetwarzania (ekran debug). Trzymane w SharedPreferences jako JSON. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ProcessingSettings> = _settings.asStateFlow()

    private fun read(): ProcessingSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(ProcessingSettings.serializer(), it) }.getOrNull() }
            ?: ProcessingSettings()

    fun update(f: (ProcessingSettings) -> ProcessingSettings) {
        val s = f(_settings.value)
        prefs.edit().putString(KEY, json.encodeToString(ProcessingSettings.serializer(), s)).apply()
        _settings.value = s
    }

    fun reset() = update { ProcessingSettings() }

    private companion object {
        const val KEY = "processing_settings"
    }
}
