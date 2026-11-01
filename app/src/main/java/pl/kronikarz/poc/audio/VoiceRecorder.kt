package pl.kronikarz.poc.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/** Nagrywanie głosu do AAC w kontenerze MPEG-4 (.m4a). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var output: File? = null

    val isRecording get() = recorder != null

    fun start(file: File) {
        check(recorder == null) { "Nagrywanie już trwa" }
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw e
        }
        recorder = r
        output = file
        startedAt = SystemClock.elapsedRealtime()
    }

    val elapsedMs: Long get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Poziom dźwięku 0..1 (skala logarytmiczna) do animacji. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp <= 0) return 0f
        // 32767 = pełna skala; ~ -50 dB traktujemy jako ciszę
        val db = 20 * ln(amp / 32767.0) / ln(10.0)
        return max(0.0, (db + 50) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** Kończy nagranie. Zwraca czas trwania albo null, gdy nagranie było za krótkie/nieudane. */
    fun stop(): Long? {
        val r = recorder ?: return null
        val duration = elapsedMs
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess // stop() rzuca, gdy nic nie zarejestrowano
        r.release()
        if (!ok || duration < 300) {
            output?.delete()
            return null
        }
        return duration
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        output?.delete()
    }
}
