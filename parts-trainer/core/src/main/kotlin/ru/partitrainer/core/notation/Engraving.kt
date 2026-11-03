package ru.partitrainer.core.notation

import ru.partitrainer.core.structure.MeasureMark

enum class Clef { TREBLE, BASS }

/** Записанная высота: ступень 0=до…6=си, альтерация −2..2, октава по научной нотации (до первой октавы = C4). */
data class Pitch(val step: Int, val alter: Int, val octave: Int) {
    /** Абсолютный номер диатонической ступени (C0=0). */
    val diatonic: Int get() = octave * 7 + step
}

/** Длительность в нотных терминах: [base] — 0 целая, 1 половинная, 2 четвертная, 3 восьмая, 4 шестнадцатая, 5 тридцать вторая. */
data class Dur(val base: Int, val dots: Int = 0, val triplet: Boolean = false) {
    val hasStem get() = base >= 1
    val flags get() = (base - 2).coerceAtLeast(0)
    val filled get() = base <= 1 // пустая головка (целая, половинная)
}

object Engraving {
    private val SHARP_PC = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)       // ступень по классу высоты (диезы)
    private val SHARP_ALT = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
    private val FLAT_PC = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)
    private val FLAT_ALT = intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)
    private val STEP_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    private val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)  // B E A D G C F

    /** Альтерация ступени по тональности [fifths] (−7..7). */
    fun keyAlter(step: Int, fifths: Int): Int = when {
        fifths > 0 -> if (step in SHARP_ORDER.take(fifths.coerceAtMost(7))) 1 else 0
        fifths < 0 -> if (step in FLAT_ORDER.take((-fifths).coerceAtMost(7))) -1 else 0
        else -> 0
    }

    /** Ступени с знаками при ключе, по порядку записи (для рисования). */
    fun keySignature(fifths: Int): List<Int> =
        if (fifths > 0) SHARP_ORDER.take(fifths.coerceAtMost(7)) else if (fifths < 0) FLAT_ORDER.take((-fifths).coerceAtMost(7)) else emptyList()

    /** Написание ноты: в бемольных тональностях — бемоли, иначе диезы. */
    fun spell(midi: Int, fifths: Int): Pitch {
        val pc = Math.floorMod(midi, 12)
        val octMidi = Math.floorDiv(midi, 12) - 1
        val flats = fifths < 0
        val step = if (flats) FLAT_PC[pc] else SHARP_PC[pc]
        val alter = if (flats) FLAT_ALT[pc] else SHARP_ALT[pc]
        return Pitch(step, alter, octMidi)
    }

    /** Положение на стане в половинах интервала от нижней линии (0 — нижняя линия, 8 — верхняя). */
    fun staffPos(p: Pitch, clef: Clef): Int {
        val bottom = when (clef) { Clef.TREBLE -> 4 * 7 + 2 /* E4 */; Clef.BASS -> 2 * 7 + 4 /* G2 */ }
        return p.diatonic - bottom
    }

    /** Добавочные линейки: позиции (чётные), которые нужно дорисовать до ноты на позиции [pos]. */
    fun ledgerLines(pos: Int): List<Int> = when {
        pos <= -2 -> (pos..-2).filter { it % 2 == 0 }
        pos >= 10 -> (10..pos).filter { it % 2 == 0 }
        else -> emptyList()
    }

    fun stemUp(pos: Int): Boolean = pos < 4

    /** Ближайшая нотная длительность для [beats] (в четвертях). */
    fun durationOf(beats: Double): Dur {
        data class C(val d: Dur, val v: Double)
        val cands = ArrayList<C>()
        for (base in 0..5) {
            val v = 4.0 / (1 shl base)
            cands += C(Dur(base), v)
            if (base <= 4) cands += C(Dur(base, 1), v * 1.5)
            if (base <= 3) cands += C(Dur(base, 2), v * 1.75)
            if (base in 1..4) cands += C(Dur(base, 0, true), v * 2.0 / 3.0)
        }
        // при равной близости предпочитаем простую длительность
        return cands.minByOrNull { Math.abs(it.v - beats) + it.d.dots * 1e-4 + (if (it.d.triplet) 1e-4 else 0.0) }!!.d
    }

    /**
     * Лиги-балки: группы подряд идущих нот короче четверти внутри одной доли, без пауз между ними.
     * [starts]/[beats] — начала и длительности нот по порядку; возвращает группы индексов (по 2+ ноты).
     */
    fun beamGroups(starts: List<Double>, beats: List<Double>): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        fun flush() { if (cur.size >= 2) groups += cur; cur = ArrayList() }
        for (i in starts.indices) {
            val short = beats[i] < 1.0 - 1e-6 && durationOf(beats[i]).hasStem
            if (!short) { flush(); continue }
            val beat = Math.floor(starts[i] + 1e-6).toInt()
            val contiguous = cur.isNotEmpty() && Math.abs(starts[cur.last()] + beats[cur.last()] - starts[i]) < 1e-6
            val sameBeat = cur.isNotEmpty() && Math.floor(starts[cur.first()] + 1e-6).toInt() == beat
            if (cur.isNotEmpty() && !(contiguous && sameBeat)) flush()
            cur += i
        }
        flush()
        return groups
    }
}

/** Соответствие развёрнутой (с повторами) и напечатанной шкалы долей. */
class PlayMap(private val marks: List<MeasureMark>, private val order: List<Int>?) {
    private class Seg(val expStart: Double, val len: Double, val origStart: Double, val measure: Int)
    private val segs: List<Seg>

    init {
        val list = ArrayList<Seg>()
        if (order != null && marks.isNotEmpty()) {
            var t = 0.0
            for (mi in order) { val m = marks[mi]; list += Seg(t, m.lengthBeats, m.startBeat, mi); t += m.lengthBeats }
        }
        segs = list
    }

    val expanded get() = segs.isNotEmpty()

    private fun segAt(expBeat: Double): Seg? =
        segs.lastOrNull { it.expStart <= expBeat + 1e-9 }

    /** Доля в напечатанных нотах для позиции [expBeat] воспроизведения. */
    fun originalBeat(expBeat: Double): Double {
        val s = segAt(expBeat) ?: return expBeat
        return s.origStart + (expBeat - s.expStart).coerceIn(0.0, s.len)
    }

    /** Индекс напечатанного такта (с нуля) для позиции воспроизведения; −1 — неизвестно. */
    fun originalMeasure(expBeat: Double): Int = segAt(expBeat)?.measure ?: -1

    /** Позиция воспроизведения для напечатанной доли — первое вхождение. */
    fun expandedBeat(origBeat: Double): Double {
        val s = segs.firstOrNull { origBeat >= it.origStart - 1e-9 && origBeat < it.origStart + it.len - 1e-9 } ?: return origBeat
        return s.expStart + (origBeat - s.origStart)
    }
}

/** Раскладка тактов по строкам стана (в абстрактных единицах длины — пикселях). */
object SystemPacker {
    class Line(val from: Int, val toExclusive: Int, val widths: List<Double>)

    /**
     * @param minWidths минимальная ширина каждого такта; [headerWidth] — место под ключ/знаки в начале строки (вычитается из ширины),
     * [available] — ширина строки. Такты растягиваются пропорционально, чтобы заполнить строку (последняя строка — если заполнена ≥ [lastFill]).
     */
    fun pack(minWidths: List<Double>, headerWidth: Double, available: Double, lastFill: Double = 0.6): List<Line> {
        val lines = ArrayList<Line>()
        var i = 0
        val room = (available - headerWidth).coerceAtLeast(1.0)
        while (i < minWidths.size) {
            var sum = 0.0
            var j = i
            while (j < minWidths.size && (j == i || sum + minWidths[j] <= room)) { sum += minWidths[j]; j++ }
            val last = j >= minWidths.size
            val k = if (last && sum < room * lastFill) 1.0 else room / sum
            lines += Line(i, j, (i until j).map { minWidths[it] * k })
            i = j
        }
        return lines
    }
}
