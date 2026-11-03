package ru.partitrainer.core.model

import kotlinx.serialization.Serializable

/** Одна нота. Паузы не хранятся — это промежутки между [startBeat] соседних нот. */
@Serializable
data class Note(
    val midi: Int,
    val startBeat: Double,
    val beats: Double,
    val measure: Int,
    val lyric: String? = null,
) {
    val endBeat: Double get() = startBeat + beats
}

@Serializable
enum class VoiceKind { SOPRANO, ALTO, TENOR, BASS, MELODY, OTHER }

@Serializable
data class Voice(
    val id: String,
    val label: String,
    val kind: VoiceKind,
    val notes: List<Note>,
)

@Serializable
data class Score(
    val title: String?,
    val keyFifths: Int,
    val timeNum: Int,
    val timeDen: Int,
    val tempo: Int?,
    val voices: List<Voice>,
) {
    /** Длина такта в долях (доля = четвертная нота). */
    val beatsPerMeasure: Double get() = timeNum * 4.0 / timeDen
}

/** Рабочая доля — четвертная нота. Допуск при сравнении долей. */
const val BEAT_EPS = 1e-6
