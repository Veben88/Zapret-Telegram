package ru.partitrainer.omr.homr

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Сверка распознанного с эталонным MusicXML (Sprint 0). Правила — как в `tools/omr_eval/compare.py`:
 *
 *  * у каждой партии берутся верхняя и нижняя «линии» нот: в каждый момент начала нот — самая высокая и самая низкая нота;
 *    ноты под лигой (tie) сливаются в одну;
 *  * последовательности сравниваются расстоянием Левенштейна: **высоты** — только высоты, **длительности** — только
 *    длительности, **высоты+длительности** — пары;
 *  * **SER** (Symbol Error Rate) — по напечатанным символам: в каждый момент верхняя нота или пауза с её длительностью, без
 *    слияния лиг; ошибка = вставки + удаления + замены, делённые на число символов эталона.
 *
 * Распознанные токены перед сверкой проходят те же исправления, что и в приложении (лишняя точка, триоли).
 */
object OmrEval {
    /** Время — в долях четверти ([TICKS] на четверть): точно для делений MusicXML (до 1024), триолей и квинтолей. */
    const val TICKS = 15360L
    private fun ticks(quarters: Double): Long = Math.round(quarters * TICKS)

    /** Нота линии: начало и длительность — в [TICKS]. */
    data class Note(val onset: Long, val midi: Int, val dur: Long)
    /** Символ для SER: высота (−1 — пауза) и длительность в [TICKS]. */
    data class Sym(val midi: Int, val dur: Long)
    class Lines(val top: List<Pair<Int, Long>>, val bottom: List<Pair<Int, Long>>)
    class Part(val lines: Lines, val symbols: List<Sym>)

    private val STEP = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    // ---------- эталон ----------

    fun readMusicXml(file: File): Map<String, Part> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = f.newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, Part>()
        val parts = doc.getElementsByTagName("part")
        for (pi in 0 until parts.length) {
            val part = parts.item(pi) as Element
            var div = 1.0; var t = 0L; var lastOnset = 0L
            val notes = ArrayList<Note>()
            val syms = ArrayList<Pair<Long, Sym>>()               // момент начала → символ
            for (m in part.children("measure")) for (el in m.children()) {
                when (el.tagName) {
                    "attributes" -> el.child("divisions")?.let { div = it.textContent.trim().toDouble() }
                    "backup" -> t -= ticks(el.child("duration")!!.textContent.trim().toDouble() / div)
                    "forward" -> t += ticks(el.child("duration")!!.textContent.trim().toDouble() / div)
                    "note" -> {
                        if (el.child("grace") != null) continue
                        val dur = ticks(el.child("duration")?.textContent?.trim()?.toDouble()?.div(div) ?: 0.0)
                        val chord = el.child("chord") != null
                        val onset = if (chord) lastOnset else t
                        val p = el.child("pitch")
                        val tieStop = el.children("tie").any { it.getAttribute("type") == "stop" }
                        if (p != null) {
                            val midi = (p.child("octave")!!.textContent.trim().toInt() + 1) * 12 + STEP.getValue(p.child("step")!!.textContent.trim()[0]) +
                                (p.child("alter")?.textContent?.trim()?.toDouble()?.toInt() ?: 0)
                            if (tieStop) {
                                val k = notes.indexOfLast { it.midi == midi }
                                if (k >= 0) notes[k] = notes[k].copy(dur = notes[k].dur + dur)
                            } else notes += Note(onset, midi, dur)
                            syms += onset to Sym(midi, dur)
                        } else if (!chord) syms += onset to Sym(-1, dur)
                        if (!chord) { lastOnset = t; t += dur }
                    }
                }
            }
            out[part.getAttribute("id")] = Part(lines(notes), topSymbols(syms))
        }
        return out
    }

    private fun Element.children(tag: String? = null): List<Element> {
        val res = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element && (tag == null || n.tagName == tag)) res += n; n = n.nextSibling }
        return res
    }
    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    fun lines(notes: List<Note>): Lines {
        val by = notes.groupBy { it.onset }.toSortedMap()
        val cmp = compareBy<Pair<Int, Long>>({ it.first }, { it.second })
        val top = by.values.map { ns -> ns.map { it.midi to it.dur }.maxWith(cmp) }
        val bottom = by.values.map { ns -> ns.map { it.midi to it.dur }.minWith(cmp) }
        return Lines(top, bottom)
    }

    /** В каждый момент — верхняя нота (или пауза, если нот нет). */
    private fun topSymbols(syms: List<Pair<Long, Sym>>): List<Sym> =
        syms.groupBy { it.first }.toSortedMap().values.map { g -> g.map { it.second }.filter { it.midi >= 0 }.maxByOrNull { it.midi } ?: g.first().second }

    // ---------- распознанное ----------

    private val KERN = Regex("(note|rest)_(\\d+)(G?)(\\.*)$")

    /** Длительность токена в четвертях (null — форшлаг, многотактовая пауза и прочее). */
    fun tokenDur(r: String): Double? {
        val m = KERN.matchEntire(r) ?: return null
        if (m.groupValues[3] == "G") return null
        val k = m.groupValues[2].toInt()
        val base = if (k == 0) 8.0 else 4.0 / k
        var d = base; var add = base / 2
        repeat(m.groupValues[4].length) { d += add; add /= 2 }
        return d
    }

    /** Строки одной партии по порядку чтения (по всем страницам) → линии и символы, со сдвигом высоты [shift]. */
    fun fromTokens(staffs: List<List<EncodedSymbol>>, shift: Int = 0): Part {
        val fixed = Triplets.restore(staffs.map { Repairs.fixDots(it) })
        data class N(val onset: Long, val midi: Int, var dur: Long, val tieStart: Boolean)
        val notes = ArrayList<N>()
        val syms = ArrayList<Pair<Long, Sym>>()
        var t = 0L
        for ((si, toks) in fixed.withIndex()) {
            val groups = ArrayList<MutableList<EncodedSymbol>>()
            var inChord = false
            for (s in toks) {
                if (s.rhythm == "chord") { inChord = true; continue }
                if (inChord && groups.isNotEmpty()) { groups.last() += s; inChord = false } else groups += mutableListOf(s)
            }
            var first = si > 0
            for (g in groups) {
                val ev = g.mapNotNull { s -> if (s.rhythm.startsWith("note_") || s.rhythm.startsWith("rest_")) tokenDur(s.rhythm)?.let { s to ticks(it) } else null }
                if (ev.isEmpty()) continue
                var topNote: Sym? = null
                for ((s, d) in ev) {
                    if (!s.rhythm.startsWith("note_")) continue
                    val mm = (TokenParser.midi(s.pitch, s.lift) ?: continue) + shift
                    if (topNote == null || mm > topNote.midi) topNote = Sym(mm, d)
                    // лига, как в приложении; на переносе строки хватает конца дуги на первой ноте
                    val prev = if (s.slur.contains("slurStop")) notes.indices.reversed().firstOrNull { k ->
                        notes[k].midi == mm && notes[k].onset + notes[k].dur == t && (notes[k].tieStart || first)
                    } else null
                    if (prev != null) notes[prev].dur += d else notes += N(t, mm, d, s.slur.contains("slurStart"))
                    first = false
                }
                syms += t to (topNote ?: Sym(-1, ev.minOf { it.second }))
                t += ev.minOf { it.second }
            }
        }
        return Part(lines(notes.map { Note(it.onset, it.midi, it.dur) }), syms.map { it.second })
    }

    // ---------- метрики ----------

    fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1); cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }

    /** Счётчики ошибок; проценты — из них, чтобы суммировать по партиям и наборам. */
    class Score(var notes: Int = 0, var pitchErr: Int = 0, var durErr: Int = 0, var bothErr: Int = 0, var symbols: Int = 0, var symErr: Int = 0) {
        fun add(o: Score) { notes += o.notes; pitchErr += o.pitchErr; durErr += o.durErr; bothErr += o.bothErr; symbols += o.symbols; symErr += o.symErr }
        val pitchAcc get() = 100.0 * (1 - pitchErr.toDouble() / maxOf(1, notes))
        val durAcc get() = 100.0 * (1 - durErr.toDouble() / maxOf(1, notes))
        val bothAcc get() = 100.0 * (1 - bothErr.toDouble() / maxOf(1, notes))
        val ser get() = 100.0 * symErr / maxOf(1, symbols)
    }

    fun score(gt: Part, ours: Part): Score {
        val s = Score()
        for ((g, o) in listOf(gt.lines.top to ours.lines.top, gt.lines.bottom to ours.lines.bottom)) {
            s.notes += g.size
            s.pitchErr += editDistance(g.map { it.first }, o.map { it.first })
            s.durErr += editDistance(g.map { it.second }, o.map { it.second })
            s.bothErr += editDistance(g, o)
        }
        s.symbols = gt.symbols.size
        s.symErr = editDistance(gt.symbols, ours.symbols)
        return s
    }
}
