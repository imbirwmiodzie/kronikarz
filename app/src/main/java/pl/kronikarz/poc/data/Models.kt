package pl.kronikarz.poc.data

import kotlinx.serialization.Serializable
import pl.kronikarz.poc.analysis.QualityBreakdown

/** Parametry przetwarzania (strojone na ukrytym ekranie debug). */
@Serializable
data class ProcessingSettings(
    /** Co ile ms próbkujemy klatkę (500 ms = 2 klatki/s). */
    val sampleIntervalMs: Int = 500,
    /** Minimalna wysokość twarzy w pikselach klatki. */
    val minFaceHeightPx: Int = 40,
    /** Próg podobieństwa (średnie powiązanie, kosinus) przy łączeniu grup w osobę. */
    val similarityThreshold: Float = 0.40f,
    /** Minimalna liczba wystąpień, żeby grupa została osobą. */
    val minOccurrences: Int = 3,
    /** true = dokładna klatka (OPTION_CLOSEST, wolniej), false = najbliższa klatka kluczowa (szybko). */
    val exactFrames: Boolean = true,
    /**
     * EKSPERYMENTALNE: sekwencyjny dekoder MediaCodec zamiast MediaMetadataRetriever.
     * Dużo szybszy dla nagrań z rzadkimi klatkami kluczowymi; przy błędzie wraca do retrievera.
     */
    val sequentialDecoder: Boolean = false,
    /** Większe klatki są zmniejszane do tej szerokości przed detekcją (HD z telefonu). */
    val maxFrameWidth: Int = 1280,
    /** Zachowaj wszystkie wycinki i embeddingi (podgląd grup i ponowne grupowanie w debug). */
    val keepTuningData: Boolean = true,
)

@Serializable
data class VideoInfo(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

@Serializable
enum class SessionStatus { PROCESSING, READY, FAILED }

@Serializable
data class ProcessingStats(
    val wallClockMs: Long,
    val videoDurationMs: Long,
    val framesSampled: Int,
    val facesDetected: Int,
    val facesAccepted: Int,
    val rejectedTooSmall: Int,
    val rejectedAtEdge: Int,
    val rejectedNoLandmarks: Int,
    val decodeMs: Long,
    val detectMs: Long,
    val embedMs: Long,
    val otherMs: Long,
    val clusterMs: Long,
    val device: String,
    /** Próbki, których dekoder nie zwrócił (null) — diagnostyka dekodera. */
    val framesFailed: Int = 0,
    /** Próbki pominięte, bo dekoder zwrócił tę samą klatkę co poprzednio. */
    val framesDuplicate: Int = 0,
) {
    /** Czas przetwarzania jednej minuty wideo (główna metryka wydajności). */
    val msPerVideoMinute: Long get() = if (videoDurationMs <= 0) 0 else wallClockMs * 60_000 / videoDurationMs
}

@Serializable
data class FaceShot(
    val faceId: Int,
    /** Ścieżka względna względem katalogu sesji, np. "faces/f_12.jpg". */
    val cropPath: String,
    val timestampMs: Long,
    val quality: Float,
)

@Serializable
enum class PersonStatus { ACTIVE, HIDDEN, MERGED }

@Serializable
data class Person(
    val id: String,
    /** Numer porządkowy do wyświetlania („Osoba 3”). */
    val number: Int,
    val bestShot: FaceShot,
    val alternateShots: List<FaceShot>,
    val occurrenceCount: Int,
    /** Znaczniki czasu wszystkich wystąpień (ms od początku nagrania), rosnąco. */
    val timestampsMs: List<Long>,
    val name: String? = null,
    val status: PersonStatus = PersonStatus.ACTIVE,
    /** Id osoby, z którą ta została scalona (gdy status == MERGED). */
    val mergedInto: String? = null,
    /** Pytania pominięte przyciskiem „Następne pytanie”. */
    val skippedQuestionIds: List<String> = emptyList(),
    /** Id wszystkich twarzy tej osoby (do podglądu w debug). */
    val faceIds: List<Int> = emptyList(),
    /** true, gdy użytkownik sam wybrał zdjęcie główne. */
    val bestShotChosenByUser: Boolean = false,
    /** Średni (znormalizowany) embedding osoby — do podpowiadania kandydatów do scalenia. */
    val centroid: FloatArray? = null,
)

@Serializable
data class Recording(
    val id: String,
    val personId: String,
    val questionId: String,
    /** Treść pytania w chwili nagrania (pytania mogą się później zmienić). */
    val questionText: String,
    /** Ścieżka względna, np. "audio/r_abc.m4a". */
    val audioPath: String,
    val durationMs: Long,
    val createdAt: Long,
)

/** Grupa odrzucona jako szum — tylko do podglądu w debug. */
@Serializable
data class RejectedGroup(val faceIds: List<Int>)

@Serializable
data class Session(
    val id: String,
    val createdAt: Long,
    val video: VideoInfo,
    val settings: ProcessingSettings,
    val status: SessionStatus = SessionStatus.PROCESSING,
    val errorMessage: String? = null,
    val persons: List<Person> = emptyList(),
    val recordings: List<Recording> = emptyList(),
    val rejectedGroups: List<RejectedGroup> = emptyList(),
    val stats: ProcessingStats? = null,
    /** Id osoby oglądanej ostatnio (wznawianie przeglądu). */
    val lastPersonId: String? = null,
) {
    /** Aktywne osoby w kolejności przeglądania: najczęściej widoczne najpierw. */
    val activePersons: List<Person>
        get() = persons.filter { it.status == PersonStatus.ACTIVE }
            .sortedWith(compareByDescending<Person> { it.occurrenceCount }.thenBy { it.number })

    fun recordingsOf(personId: String) = recordings.filter { it.personId == personId }.sortedBy { it.createdAt }
}

/** Dane jednej twarzy zapisywane do strojenia (faces.json, gdy keepTuningData). */
@Serializable
data class FaceRecord(
    val id: Int,
    val frameIndex: Int,
    val timestampMs: Long,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val quality: QualityBreakdown,
    val cropPath: String,
    val embedding: FloatArray,
)

@Serializable
data class FaceRecordFile(val faces: List<FaceRecord>)
