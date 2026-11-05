package ru.partitrainer.core.edit

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.structure.MeasureMark

/** История правок с отменой/повтором. */
class EditHistory<T>(initial: T, private val limit: Int = 200) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        current = next
        redoStack.clear()
    }

    fun undo(): T { if (canUndo) { redoStack.addLast(current); current = undoStack.removeLast() }; return current }
    fun redo(): T { if (canRedo) { undoStack.addLast(current); current = redoStack.removeLast() }; return current }
}

/** Чистые функции правки нот одного голоса. Нумерация тактов пересчитывается по [measureOf]. */
class NoteEditor(private val measureOf: (Double) -> Int) {

    companion object {
        fun forMarks(marks: List<MeasureMark>, beatsPerMeasure: Double): NoteEditor = NoteEditor { beat ->
            if (marks.isEmpty()) Math.floor(beat / beatsPerMeasure + 1e-9).toInt() + 1
            else (marks.indexOfLast { it.startBeat <= beat + 1e-9 } + 1).coerceAtLeast(1)
        }
    }

    private fun fix(list: List<Note>): List<Note> =
        list.sortedBy { it.startBeat }.map { it.copy(measure = measureOf(it.startBeat)) }

    fun setPitch(notes: List<Note>, i: Int, midi: Int): List<Note> =
        notes.mapIndexed { k, n -> if (k == i) n.copy(midi = midi.coerceIn(0, 127)) else n }

    fun shiftPitch(notes: List<Note>, i: Int, semitones: Int): List<Note> = setPitch(notes, i, notes[i].midi + semitones)

    /** Меняет длительность; следующая нота при перекрытии подрезается/сдвигается не будет — ограничиваем длительность до начала следующей. */
    fun setDuration(notes: List<Note>, i: Int, beats: Double): List<Note> {
        val next = notes.getOrNull(i + 1)
        val maxBeats = if (next != null) next.startBeat - notes[i].startBeat else Double.MAX_VALUE
        val b = beats.coerceIn(0.125, maxBeats.coerceAtLeast(0.125))
        return notes.mapIndexed { k, n -> if (k == i) n.copy(beats = b) else n }
    }

    fun setLyric(notes: List<Note>, i: Int, lyric: String?): List<Note> =
        notes.mapIndexed { k, n -> if (k == i) n.copy(lyric = lyric?.ifBlank { null }) else n }

    /** Нота → пауза: удаляем ноту, промежуток остаётся. */
    fun toRest(notes: List<Note>, i: Int): List<Note> = notes.filterIndexed { k, _ -> k != i }
    fun delete(notes: List<Note>, i: Int): List<Note> = toRest(notes, i)

    /** Пауза → нота: вставляет ноту в промежуток (обрезается по следующей). */
    fun insert(notes: List<Note>, midi: Int, startBeat: Double, beats: Double): List<Note> {
        val next = notes.firstOrNull { it.startBeat > startBeat - 1e-9 }
        val prev = notes.lastOrNull { it.startBeat < startBeat - 1e-9 }
        var b = beats
        if (next != null && next.startBeat < startBeat + b) b = next.startBeat - startBeat
        var list = notes
        if (prev != null && prev.endBeat > startBeat + 1e-9) {
            list = list.map { if (it === prev) it.copy(beats = startBeat - it.startBeat) else it }
        }
        if (b <= 1e-9) return notes
        return fix(list + Note(midi.coerceIn(0, 127), startBeat, b, measureOf(startBeat)))
    }

    /** Разделить ноту на две в точке [at] долей от её начала (по умолчанию пополам). */
    fun split(notes: List<Note>, i: Int, at: Double = notes[i].beats / 2): List<Note> {
        val n = notes[i]
        if (at <= 1e-9 || at >= n.beats - 1e-9) return notes
        val a = n.copy(beats = at)
        val b = n.copy(startBeat = n.startBeat + at, beats = n.beats - at, lyric = null, measure = measureOf(n.startBeat + at))
        return notes.take(i) + a + b + notes.drop(i + 1)
    }

    /** Объединить ноту i со следующей (высота первой; промежуток между ними закрывается). Это же — лига. */
    fun mergeWithNext(notes: List<Note>, i: Int): List<Note> {
        val a = notes.getOrNull(i) ?: return notes
        val b = notes.getOrNull(i + 1) ?: return notes
        val merged = a.copy(beats = b.endBeat - a.startBeat)
        return notes.take(i) + merged + notes.drop(i + 2)
    }

    /** Уместить ноту в границы такта? Нет — проверка суммы по тактам. */
    fun measureSums(notes: List<Note>): Map<Int, Double> = notes.groupBy { it.measure }.mapValues { (_, v) -> v.sumOf { it.beats } }
}
