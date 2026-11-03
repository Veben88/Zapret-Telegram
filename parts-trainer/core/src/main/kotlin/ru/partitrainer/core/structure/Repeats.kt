package ru.partitrainer.core.structure

import kotlinx.serialization.Serializable
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice

@Serializable
enum class Jump(val label: String) {
    DC("D.C."), DS("D.S."),
    DC_AL_CODA("D.C. al Coda"), DS_AL_CODA("D.S. al Coda"),
    DC_AL_FINE("D.C. al Fine"), DS_AL_FINE("D.S. al Fine");

    val toStart: Boolean get() = this == DC || this == DC_AL_CODA || this == DC_AL_FINE
}

/** Структурная разметка одного такта (в порядке записи в нотах). */
@Serializable
data class MeasureMark(
    val startBeat: Double,
    val lengthBeats: Double,
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    /** Сколько всего раз играется секция (по умолчанию 2: основной проход + повтор). */
    val repeatTimes: Int = 2,
    /** Номера вольт (1-я, 2-я…); пусто — такт вне вольты. */
    val volta: List<Int> = emptyList(),
    val segno: Boolean = false,
    val coda: Boolean = false,
    val toCoda: Boolean = false,
    val fine: Boolean = false,
    /** Переход, выполняемый в конце этого такта. */
    val jump: Jump? = null,
)

data class Expansion(
    /** Индексы тактов (с нуля) в порядке исполнения. */
    val order: List<Int>,
    /** true, если структуру не удалось определить однозначно. */
    val ambiguous: Boolean,
    val warnings: List<String>,
)

object Repeats {
    fun hasStructure(marks: List<MeasureMark>) = marks.any {
        it.repeatStart || it.repeatEnd || it.volta.isNotEmpty() || it.segno || it.coda ||
            it.toCoda || it.fine || it.jump != null
    }

    fun expandRepeats(marks: List<MeasureMark>): Expansion {
        val n = marks.size
        val warnings = ArrayList<String>()
        if (n == 0) return Expansion(emptyList(), false, warnings)
        val segnoIdx = marks.indexOfFirst { it.segno }
        val codaIdx = marks.indexOfLast { it.coda }
        val lastEnding = marks.flatMap { it.volta }.maxOrNull() ?: 0

        // Проверка структуры.
        for (m in marks) {
            val j = m.jump ?: continue
            if (!j.toStart && segnoIdx < 0) warnings += "${j.label}: в нотах нет знака сеньо"
            if (j == Jump.DS_AL_CODA || j == Jump.DC_AL_CODA) {
                if (codaIdx < 0) warnings += "${j.label}: не найден знак коды"
                if (marks.none { it.toCoda }) warnings += "${j.label}: нет отметки «To Coda»"
            }
            if ((j == Jump.DS_AL_FINE || j == Jump.DC_AL_FINE) && marks.none { it.fine }) warnings += "${j.label}: нет отметки Fine"
        }
        if (marks.count { it.jump != null } > 1) warnings += "Несколько переходов (D.C./D.S.) — исполняется только первый"
        if (marks.count { it.segno } > 1) warnings += "Несколько знаков сеньо"
        if (marks.any { it.volta.isNotEmpty() && marks.none { m -> m.repeatEnd } }) warnings += "Вольты без знака повтора"
        var ambiguous = warnings.isNotEmpty()

        val order = ArrayList<Int>()
        var i = 0
        var sectionStart = 0
        var pass = 1
        var jumped = false
        var steps = 0
        val maxSteps = n * 12 + 100
        while (i in 0 until n) {
            if (++steps > maxSteps) {
                warnings += "Превышен предел шагов — структура зациклена"
                ambiguous = true
                break
            }
            val m = marks[i]
            if (m.volta.isNotEmpty()) {
                val plays = if (jumped) lastEnding in m.volta else pass in m.volta
                if (!plays) { i++; continue }
            }
            if (m.repeatStart && !jumped) sectionStart = i
            order += i
            if (jumped) {
                if (m.toCoda && codaIdx >= 0 && codaIdx != i) { i = codaIdx; continue }
                if (m.fine) break
            }
            if (m.repeatEnd && !jumped) {
                if (pass < m.repeatTimes) { pass++; i = sectionStart; continue }
                pass = 1
                sectionStart = i + 1
            }
            val j = m.jump
            if (j != null && !jumped) {
                val target = if (j.toStart) 0 else segnoIdx
                if (target < 0) { i++; continue } // сеньо не найдено — идём дальше
                jumped = true
                i = target
                continue
            }
            i++
        }
        return Expansion(order, ambiguous, warnings)
    }


    /** Применяет порядок исполнения к нотам: возвращает новую партитуру с развёрнутыми повторами. */
    fun applyToScore(score: Score, marks: List<MeasureMark>, order: List<Int>): Score =
        score.copy(voices = score.voices.map { it.copy(notes = applyToNotes(it.notes, marks, order)) })

    fun applyToNotes(notes: List<Note>, marks: List<MeasureMark>, order: List<Int>): List<Note> {
        val byMeasure = notes.groupBy { it.measure }
        val out = ArrayList<Note>(notes.size * 2)
        var t = 0.0
        for ((k, mi) in order.withIndex()) {
            val m = marks[mi]
            for (nt in byMeasure[mi + 1].orEmpty()) {
                out += nt.copy(startBeat = t + (nt.startBeat - m.startBeat), measure = k + 1)
            }
            t += m.lengthBeats
        }
        return out
    }

    /** Удобно для тестов и UI: итоговая партия «как в нотах» или «развёрнуто». */
    fun resolve(score: Score, marks: List<MeasureMark>, expand: Boolean): Score =
        if (!expand || marks.isEmpty()) score else applyToScore(score, marks, expandRepeats(marks).order)

    fun voiceNotesExpanded(voice: Voice, marks: List<MeasureMark>): List<Note> =
        applyToNotes(voice.notes, marks, expandRepeats(marks).order)
}
