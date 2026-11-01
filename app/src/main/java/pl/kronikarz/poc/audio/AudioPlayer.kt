package pl.kronikarz.poc.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Odtwarzanie jednego nagrania naraz. [playingId] = id aktualnie odtwarzanego nagrania. */
class AudioPlayer {
    private var player: MediaPlayer? = null
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    fun toggle(id: String, file: File) {
        if (_playingId.value == id) {
            stop(); return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            _playingId.value = id
        } catch (e: Exception) {
            p.release()
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        _playingId.value = null
    }
}
