package pl.kronikarz.poc.analysis

data class ScoredShot(val faceId: Int, val timestampMs: Long, val quality: Float)

data class ShotSelection(val best: ScoredShot, val alternates: List<ScoredShot>)

/**
 * Wybór najlepszego ujęcia osoby i 2–3 alternatyw z INNYCH momentów nagrania
 * (sąsiednie klatki co 0,5 s wyglądają prawie tak samo, więc nie są dobrą alternatywą).
 */
object ShotSelector {
    fun select(shots: List<ScoredShot>, alternates: Int = 3, minGapMs: Long = 3_000): ShotSelection {
        require(shots.isNotEmpty())
        val sorted = shots.sortedWith(compareByDescending<ScoredShot> { it.quality }.thenBy { it.timestampMs })
        val chosen = mutableListOf(sorted.first())
        for (s in sorted.drop(1)) {
            if (chosen.size > alternates) break
            if (chosen.all { kotlin.math.abs(it.timestampMs - s.timestampMs) >= minGapMs }) chosen += s
        }
        return ShotSelection(chosen.first(), chosen.drop(1))
    }
}
