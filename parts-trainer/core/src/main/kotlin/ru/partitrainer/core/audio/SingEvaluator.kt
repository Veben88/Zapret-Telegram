package ru.partitrainer.core.audio

import ru.partitrainer.core.model.Note

enum class NoteOutcome { HIT, HIGH, LOW, MISSED }

data class NoteResult(val note: Note, val outcome: NoteOutcome, val avgCents: Double?)

data class PhraseResult(val results: List<NoteResult>) {
    val percent: Int get() = if (results.isEmpty()) 0 else Math.round(100.0 * results.count { it.outcome == NoteOutcome.HIT } / results.size).toInt()
}

/** Замер: момент времени (в долях от начала фразы) и распознанная частота (или null — тишина). */
data class PitchSample(val beat: Double, val frequency: Double?)

/**
 * Режим «Спой фразу»: для каждой ноты берём замеры из её окна (без краёв — реакция/затухание),
 * считаем медиану отклонения в центах и выносим вердикт. Если пригодных замеров слишком мало — «пропустил».
 */
object SingEvaluator {
    fun evaluate(
        notes: List<Note>,
        samples: List<PitchSample>,
        toleranceCents: Double = 40.0,
        matchOctave: Boolean = false,
        edgeFraction: Double = 0.2,
        maxEdgeBeats: Double = 0.25,
        minVoicedFraction: Double = 0.3,
    ): PhraseResult {
        val res = notes.map { n ->
            val edge = minOf(n.beats * edgeFraction, maxEdgeBeats)
            val from = n.startBeat + edge
            val to = n.endBeat - edge * 0.5
            val inWindow = samples.filter { it.beat >= from && it.beat <= to }
            val voiced = inWindow.mapNotNull { it.frequency }
            if (inWindow.isEmpty() || voiced.size < minVoicedFraction * inWindow.size || voiced.isEmpty()) {
                NoteResult(n, NoteOutcome.MISSED, null)
            } else {
                val cents = voiced.map { PitchMath.deviationCents(it, n.midi, matchOctave) }.sorted()
                val med = cents[cents.size / 2]
                val o = when (Judge.verdict(med, toleranceCents)) {
                    Verdict.IN_TUNE -> NoteOutcome.HIT; Verdict.HIGH -> NoteOutcome.HIGH; Verdict.LOW -> NoteOutcome.LOW
                }
                NoteResult(n, o, med)
            }
        }
        return PhraseResult(res)
    }
}
