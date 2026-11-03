package ru.partitrainer.omr.homr

import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.omr.OmrEvent
import ru.partitrainer.core.omr.OmrMeasure
import ru.partitrainer.core.omr.OmrParsed
import kotlin.math.abs
import kotlin.math.roundToInt

/** Превращает токены трансформера (по нотоносцам) в такты [OmrParsed] (общий формат с Claude Vision). */
object TokenParser {
    private val kern = Regex("(note|rest)_(\\d+)(G?)(\\.*)")
    private val multiRest = Regex("rest_(\\d+)m")

    /** Длительность в долях (четверть = 1); null — форшлаг/нераспознано. */
    fun beats(rhythm: String): Double? {
        val m = kern.matchEntire(rhythm) ?: return null
        if (m.groupValues[3] == "G") return null
        val k = m.groupValues[2].toInt()
        val base = if (k == 0) 8.0 else 4.0 / k
        var d = base; var add = base / 2
        repeat(m.groupValues[4].length) { d += add; add /= 2 }
        return d
    }

    fun midi(pitch: String, lift: String): Int? {
        val base = NoteNames.parse(pitch) ?: return null
        val alter = when (lift) { "#" -> 1; "##" -> 2; "b" -> -1; "bb" -> -2; else -> 0 }
        return (base + alter).coerceIn(0, 127)
    }

    private class Ev(val midi: Int?, val beats: Double, var tie: Boolean, val slur: String)

    private class Builder {
        val events = ArrayList<Ev>()
        var repeatStart = false; var repeatEnd = false
        var ending: Int? = null
        fun total() = events.sumOf { it.beats }
    }

    /** @param staffs токены каждого нотоносца в порядке чтения. */
    fun parse(staffs: List<List<EncodedSymbol>>): OmrParsed {
        var fifths: Int? = null
        var den: Int? = null
        val measures = ArrayList<Builder>()
        var cur = Builder()
        var volta = 0; var lastVoltaMeasure = -10; var inVolta = false
        var prevEvent: Ev? = null

        fun closeMeasure() {
            if (cur.events.isNotEmpty() || cur.repeatStart || cur.repeatEnd) measures += cur
            if (inVolta) { /* вольта продолжается до voltaStop */ }
            cur = Builder().also { if (inVolta) it.ending = volta }
        }

        for (symbols in staffs) {
            // группы аккордов: символ после «chord» относится к предыдущей группе
            val groups = ArrayList<MutableList<EncodedSymbol>>()
            var inChord = false
            for (s in symbols) {
                if (s.rhythm == "chord") inChord = true
                else if (inChord && groups.isNotEmpty()) { groups.last() += s; inChord = false }
                else groups += mutableListOf(s)
            }
            for (g in groups) {
                val first = g[0]
                val r = first.rhythm
                when {
                    r.startsWith("keySignature_") -> if (fifths == null) fifths = r.substringAfter('_').toIntOrNull()
                    r.startsWith("timeSignature/") -> if (den == null) den = r.substringAfter('/').toIntOrNull()
                    r == "barline" || r == "doublebarline" || r == "bolddoublebarline" -> { closeMeasure(); prevEvent = null }
                    r == "repeatStart" -> { closeMeasure(); cur.repeatStart = true }
                    r == "repeatEnd" -> { cur.repeatEnd = true; closeMeasure() }
                    r == "repeatEndStart" -> { cur.repeatEnd = true; closeMeasure(); cur.repeatStart = true }
                    r.startsWith("voltaStart") -> {
                        volta = if (measures.size == lastVoltaMeasure + 1) volta + 1 else 1
                        inVolta = true; cur.ending = volta
                    }
                    r.startsWith("voltaStop") || r.startsWith("voltaDiscontinue") -> { lastVoltaMeasure = measures.size; inVolta = false }
                    multiRest.matches(r) -> { /* многотактовая пауза: отдельные такты нам не нужны */ }
                    r.startsWith("note_") || r.startsWith("rest_") -> {
                        val b = beats(r) ?: continue
                        if (r.startsWith("rest_")) { cur.events += Ev(null, b, false, ""); prevEvent = null; continue }
                        // из аккорда берём верхнюю ноту
                        val best = g.filter { it.rhythm.startsWith("note_") }
                            .mapNotNull { n -> midi(n.pitch, n.lift)?.let { it to n } }
                            .maxByOrNull { it.first } ?: continue
                        val ev = Ev(best.first, b, false, best.second.slur)
                        // лига: slurStart на предыдущей ноте и slurStop на этой с той же высотой
                        val p = prevEvent
                        if (p != null && p.midi == ev.midi && p.slur.contains("slurStart") && ev.slur.contains("slurStop")) p.tie = true
                        cur.events += ev; prevEvent = ev
                    }
                    else -> {}
                }
            }
            // конец строки: открытый такт с нотами закрываем
            if (cur.events.isNotEmpty()) { closeMeasure(); prevEvent = null }
        }
        if (cur.events.isNotEmpty() || cur.repeatEnd) measures += cur

        // Размер: знаменатель из токена, числитель — по самому частому полному такту.
        val d = den ?: 4
        val totals = measures.map { it.total() }.filter { it > 0 }
        val common = totals.groupingBy { (it * 48).roundToInt() }.eachCount().maxByOrNull { it.value }?.key?.let { it / 48.0 } ?: 4.0
        val num = (common * d / 4.0).roundToInt().coerceAtLeast(1)
        val barLen = num * 4.0 / d

        val out = measures.mapIndexed { i, b ->
            OmrMeasure(
                n = i + 1, chord = null,
                pickup = i == 0 && b.total() < barLen - 0.02 && measures.size > 1,
                events = b.events.map { OmrEvent(it.midi, it.beats, it.tie, null) },
                repeatStart = b.repeatStart, repeatEnd = b.repeatEnd, ending = b.ending, marks = emptyList(),
            )
        }
        return OmrParsed(null, fifths ?: 0, num, d, null, out)
    }
}
