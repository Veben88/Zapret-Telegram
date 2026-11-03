package ru.partitrainer.omr.homr

import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.omr.OmrEvent
import ru.partitrainer.core.omr.OmrMeasure
import ru.partitrainer.core.omr.OmrParsed
import kotlin.math.abs
import kotlin.math.roundToInt

/** Превращает токены трансформера (по нотоносцам) в такты [OmrParsed] (общий формат с Claude Vision). */
object TokenParser {
    /** Служебный символ: пауза на целый такт (для голоса, опущенного в системе). Длина подставляется после разбора. */
    const val REST_MEASURE = "rest_measure"
    private const val FILL = -1.0
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

    private class Ev(val midi: Int?, val beats: Double, var tie: Boolean, val slur: String, var syl: String? = null)

    private class Builder {
        val events = ArrayList<Ev>()
        var repeatStart = false; var repeatEnd = false
        var ending: Int? = null
        fun total() = events.sumOf { if (it.beats == FILL) 0.0 else it.beats }
    }

    /**
     * Сколько нот нотоносца могут нести слог: все ноты, кроме продолжений лиг (залигованная нота поётся одним слогом).
     */
    fun syllableSlots(staff: List<EncodedSymbol>): Int =
        parse(listOf(staff)).measures.flatMap { it.events }.let { evs ->
            var prevTied = false
            var n = 0
            for (e in evs) {
                if (e.midi == null) { prevTied = false; continue }
                if (!prevTied) n++
                prevTied = e.tie
            }
            n
        }

    /**
     * @param staffs токены каждого нотоносца в порядке чтения.
     * @param syllables слоги по нотоносцам (параллельно [staffs]), по одному на «слоговую» ноту (см. [syllableSlots]); null — без слога.
     */
    fun parse(staffs: List<List<EncodedSymbol>>, transpose: Int = 0, syllables: List<List<String?>> = emptyList()): OmrParsed {
        var fifths: Int? = null
        val sigs = java.util.TreeMap<Int, Int>()   // номер такта → знаменатель размера
        val pendingSigs = ArrayList<Int>()          // знаки размера, ещё не привязанные к такту
        val measures = ArrayList<Builder>()
        fun commitSigs() { for (d in pendingSigs) sigs[measures.size] = d; pendingSigs.clear() }
        var cur = Builder()
        var volta = 0; var lastVoltaStopSize = -10; var inVolta = false
        var prevEvent: Ev? = null

        fun closeMeasure() {
            if (cur.events.isNotEmpty() || cur.repeatStart || cur.repeatEnd) measures += cur
            if (inVolta) { /* вольта продолжается до voltaStop */ }
            cur = Builder().also { if (inVolta) it.ending = volta }
        }

        for ((staffIdx, symbols) in staffs.withIndex()) {
            val sylQueue = syllables.getOrNull(staffIdx).orEmpty()
            var sylPos = 0
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
                    r.startsWith("timeSignature/") -> r.substringAfter('/').toIntOrNull()?.let { pendingSigs += it }
                    r == "barline" || r == "doublebarline" || r == "bolddoublebarline" -> closeMeasure() // лига может пересекать тактовую черту
                    r == "repeatStart" -> { closeMeasure(); cur.repeatStart = true }
                    r == "repeatEnd" -> { cur.repeatEnd = true; closeMeasure() }
                    r == "repeatEndStart" -> { cur.repeatEnd = true; closeMeasure(); cur.repeatStart = true }
                    r.startsWith("voltaStart") -> {
                        volta = if (measures.size == lastVoltaStopSize) volta + 1 else 1
                        inVolta = true; cur.ending = volta
                    }
                    r.startsWith("voltaStop") || r.startsWith("voltaDiscontinue") -> {
                        lastVoltaStopSize = measures.size; inVolta = false
                        if (cur.events.isEmpty()) cur.ending = null
                    }
                    multiRest.matches(r) -> { /* многотактовая пауза: отдельные такты нам не нужны */ }
                    r == REST_MEASURE -> { commitSigs(); cur.events += Ev(null, FILL, false, ""); prevEvent = null }
                    r.startsWith("note_") || r.startsWith("rest_") -> {
                        val b = beats(r) ?: continue
                        commitSigs()
                        if (r.startsWith("rest_")) { cur.events += Ev(null, b, false, ""); prevEvent = null; continue }
                        // из аккорда берём верхнюю ноту
                        val best = g.filter { it.rhythm.startsWith("note_") }
                            .mapNotNull { n -> midi(n.pitch, n.lift)?.let { it to n } }
                            .maxByOrNull { it.first } ?: continue
                        val ev = Ev(((best.first + transpose).coerceIn(0, 127)), b, false, best.second.slur)
                        // лига: slurStart на предыдущей ноте и slurStop на этой с той же высотой
                        val p = prevEvent
                        val continuation = p != null && p.midi == ev.midi && p.slur.contains("slurStart") && ev.slur.contains("slurStop")
                        if (continuation) p!!.tie = true else ev.syl = sylQueue.getOrNull(sylPos++)
                        cur.events += ev; prevEvent = ev
                    }
                    else -> {}
                }
            }
            // конец строки: открытый такт с нотами закрываем
            if (cur.events.isNotEmpty()) closeMeasure()
            commitSigs()   // «запасной» знак в конце строки — для следующего такта
        }
        if (cur.events.isNotEmpty() || cur.repeatEnd) measures += cur

        // Размер: знаменатель из токена, числитель — по первому такту, начинающемуся с этого знака;
        // если такта нет — по самому частому полному такту. Метр может меняться по ходу пьесы.
        val firstDen = sigs.firstEntry()?.value ?: 4
        val totals = measures.filter { b -> b.events.none { it.beats == FILL } }.map { it.total() }.filter { it > 0 }
        val common = totals.groupingBy { (it * 48).roundToInt() }.eachCount().maxByOrNull { it.value }?.key?.let { it / 48.0 } ?: 4.0
        class Meter(val num: Int, val den: Int) { val len get() = num * 4.0 / den }
        val meterAt = java.util.TreeMap<Int, Meter>()
        for ((idx, d) in sigs) {
            val m = measures.getOrNull(idx)
            val t = if (m != null && m.events.none { it.beats == FILL } && m.total() > 0) m.total() else common
            meterAt[idx] = Meter((t * d / 4.0).roundToInt().coerceAtLeast(1), d)
        }
        // до первого знака размера (страница начинается посреди пьесы) метр определяем по содержанию тактов
        val prefixMeter = Meter(common.roundToInt().coerceAtLeast(1), 4)
        val globalMeter = if (meterAt.isEmpty() || meterAt.firstKey() > 0) (if (meterAt.isEmpty()) Meter((common * firstDen / 4.0).roundToInt().coerceAtLeast(1), firstDen) else prefixMeter) else meterAt.firstEntry().value
        fun meterFor(i: Int): Meter = meterAt.floorEntry(i)?.value ?: globalMeter

        // Знак размера в конце строки модель часто не читает. Если такт без знака имеет иную длину, чем текущий метр,
        // а следующий такт совпадает с ним по длине — считаем это сменой метра (числитель при том же знаменателе).
        val implicit = java.util.TreeMap<Int, Meter>()
        for (i in 1 until measures.size - 1) {
            if (sigs.containsKey(i) || implicit.containsKey(i)) continue
            val latest = listOfNotNull(meterAt.floorEntry(i), implicit.floorEntry(i)).maxByOrNull { it.key }?.value ?: globalMeter
            val a = measures[i]; val b = measures[i + 1]
            if (a.events.any { it.beats == FILL } || b.events.any { it.beats == FILL }) continue
            val ta = a.total(); val tb = b.total()
            if (Math.abs(ta - latest.len) > 0.02 && Math.abs(ta - tb) < 0.02 && ta > 0) {
                val num = (ta * latest.den / 4.0).roundToInt()
                if (num >= 1 && Math.abs(num * 4.0 / latest.den - ta) < 0.02) implicit[i] = Meter(num, latest.den)
            }
        }
        for ((k, v) in implicit) meterAt[k] = v

        val out = measures.mapIndexed { i, b ->
            val mt = meterFor(i)
            OmrMeasure(
                n = i + 1, chord = null,
                pickup = i == 0 && b.events.none { it.beats == FILL } && b.total() < mt.len - 0.02 && measures.size > 1,
                events = b.events.map { OmrEvent(it.midi, if (it.beats == FILL) mt.len else it.beats, it.tie, it.syl) },
                repeatStart = b.repeatStart, repeatEnd = b.repeatEnd, ending = b.ending, marks = emptyList(),
                timeNum = mt.num, timeDen = mt.den,
            )
        }
        return OmrParsed(null, fifths ?: 0, globalMeter.num, globalMeter.den, null, out)
    }
}
