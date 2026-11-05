package ru.partitrainer.core.edit

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.structure.MeasureMark

sealed interface Cell {
    val start: Double
    val beats: Double
}

data class NoteCell(val index: Int, val note: Note) : Cell {
    override val start get() = note.startBeat
    override val beats get() = note.beats
}

data class RestCell(override val start: Double, override val beats: Double) : Cell

/** Такт для списка в редакторе: ноты и паузы (промежутки) по порядку. */
data class MeasureView(
    val index: Int,
    val number: Int,
    val start: Double,
    val length: Double,
    val cells: List<Cell>,
    /** Сумма длительностей нот и пауз, начавшихся в этом такте. */
    val filled: Double,
) {
    val balanced get() = Math.abs(filled - length) < 0.02
}

object MeasureLayout {
    private const val EPS = 1e-6

    fun build(notes: List<Note>, marks: List<MeasureMark>, beatsPerMeasure: Double): List<MeasureView> {
        val bounds: List<Pair<Double, Double>> = if (marks.isNotEmpty()) marks.map { it.startBeat to it.lengthBeats } else {
            val end = notes.maxOfOrNull { it.endBeat } ?: 0.0
            val n = Math.ceil(end / beatsPerMeasure - EPS).toInt().coerceAtLeast(1)
            List(n) { it * beatsPerMeasure to beatsPerMeasure }
        }
        val out = ArrayList<MeasureView>()
        var ni = 0
        for ((mi, b) in bounds.withIndex()) {
            val (start, len) = b
            val end = start + len
            val cells = ArrayList<Cell>()
            var cursor = start
            var filled = 0.0
            while (ni < notes.size && notes[ni].startBeat < end - EPS) {
                val n = notes[ni]
                if (n.startBeat > cursor + EPS) { val r = RestCell(cursor, n.startBeat - cursor); cells += r; filled += r.beats }
                cells += NoteCell(ni, n); filled += n.beats
                cursor = maxOf(cursor, n.endBeat)
                ni++
            }
            val lastMeasure = mi == bounds.lastIndex
            if (cursor < end - EPS && !(lastMeasure && cells.any { it is NoteCell })) {
                val r = RestCell(cursor, end - cursor); cells += r; filled += r.beats
            }
            out += MeasureView(mi, mi + 1, start, len, cells, filled)
        }
        return out
    }
}
