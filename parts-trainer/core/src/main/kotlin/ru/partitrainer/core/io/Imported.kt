package ru.partitrainer.core.io

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.structure.MeasureMark

/** Нота до классификации голосов. */
data class RawNote(val note: Note, val staff: Int = 1, val voice: String = "1")

data class RawPart(val name: String, val instrument: String, val notes: List<RawNote>)

/** Результат импорта: партитура «как написано» + структура для раскрытия повторов. */
data class ImportedPiece(
    val score: Score,
    val marks: List<MeasureMark>,
)
