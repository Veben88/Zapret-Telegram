package ru.partitrainer.core.io

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice

/** Экспорт MusicXML (partwise 3.1): по одной партии на голос, длинные ноты делятся лигами по тактам. */
object MusicXmlWriter {
    private const val DIV = 48 // делений на долю

    fun write(score: Score, voices: List<Voice> = score.voices): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<score-partwise version=\"3.1\">\n")
        score.title?.let { sb.append("  <work><work-title>${esc(it)}</work-title></work>\n") }
        sb.append("  <part-list>\n")
        voices.forEachIndexed { i, v -> sb.append("    <score-part id=\"P${i + 1}\"><part-name>${esc(v.label)}</part-name></score-part>\n") }
        sb.append("  </part-list>\n")
        voices.forEachIndexed { i, v -> part(sb, score, v, i + 1) }
        sb.append("</score-partwise>\n")
        return sb.toString()
    }

    private fun part(sb: StringBuilder, score: Score, v: Voice, no: Int) {
        sb.append("  <part id=\"P$no\">\n")
        val bpm = score.beatsPerMeasure
        val total = v.notes.maxOfOrNull { it.endBeat } ?: 0.0
        val nMeasures = Math.ceil(total / bpm - 1e-9).toInt().coerceAtLeast(1)
        // Нарезка нот по тактам: (измерение, старт внутри такта в делениях, длина, лиги, нота)
        class Piece(val start: Int, val dur: Int, val tieStart: Boolean, val tieStop: Boolean, val note: Note, val first: Boolean)
        val per = Array(nMeasures) { ArrayList<Piece>() }
        for (n in v.notes.sortedBy { it.startBeat }) {
            var s = Math.round(n.startBeat * DIV).toInt()
            var remain = Math.round(n.beats * DIV).toInt().coerceAtLeast(1)
            val mLen = Math.round(bpm * DIV).toInt()
            var first = true
            while (remain > 0) {
                val m = (s / mLen).coerceAtMost(nMeasures - 1)
                val inM = s - m * mLen
                val take = minOf(remain, mLen - inM).coerceAtLeast(1)
                per[m] += Piece(inM, take, tieStart = remain > take, tieStop = !first, note = n, first = first)
                s += take; remain -= take; first = false
            }
        }
        for (m in 0 until nMeasures) {
            sb.append("    <measure number=\"${m + 1}\">\n")
            if (m == 0) {
                sb.append("      <attributes><divisions>$DIV</divisions><key><fifths>${score.keyFifths}</fifths></key>")
                sb.append("<time><beats>${score.timeNum}</beats><beat-type>${score.timeDen}</beat-type></time>")
                sb.append("<clef><sign>G</sign><line>2</line></clef></attributes>\n")
                score.tempo?.let { sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>$it</per-minute></metronome></direction-type><sound tempo=\"$it\"/></direction>\n") }
            }
            var cur = 0
            val mLen = Math.round(bpm * DIV).toInt()
            for (p in per[m].sortedBy { it.start }) {
                if (p.start < cur) continue // перекрытие внутри одного голоса пропускаем
                if (p.start > cur) rest(sb, p.start - cur)
                sb.append("      <note>")
                sb.append(pitch(p.note.midi, score.keyFifths))
                sb.append("<duration>${p.dur}</duration>")
                if (p.tieStop) sb.append("<tie type=\"stop\"/>")
                if (p.tieStart) sb.append("<tie type=\"start\"/>")
                typeOf(p.dur)?.let { sb.append("<type>${it.first}</type>"); if (it.second) sb.append("<dot/>") }
                if (p.tieStart || p.tieStop) {
                    sb.append("<notations>")
                    if (p.tieStop) sb.append("<tied type=\"stop\"/>")
                    if (p.tieStart) sb.append("<tied type=\"start\"/>")
                    sb.append("</notations>")
                }
                if (p.first && !p.note.lyric.isNullOrBlank()) {
                    val l = p.note.lyric!!
                    val syl = if (l.endsWith("-")) "begin" else "single"
                    sb.append("<lyric number=\"1\"><syllabic>$syl</syllabic><text>${esc(l.trimEnd('-'))}</text></lyric>")
                }
                sb.append("</note>\n")
                cur = p.start + p.dur
            }
            if (cur < mLen) rest(sb, mLen - cur)
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n")
    }

    private fun rest(sb: StringBuilder, dur: Int) {
        sb.append("      <note><rest/><duration>$dur</duration>")
        typeOf(dur)?.let { sb.append("<type>${it.first}</type>"); if (it.second) sb.append("<dot/>") }
        sb.append("</note>\n")
    }

    private fun typeOf(dur: Int): Pair<String, Boolean>? {
        val table = listOf("whole" to 4.0, "half" to 2.0, "quarter" to 1.0, "eighth" to 0.5, "16th" to 0.25)
        for ((n, b) in table) {
            if (dur == Math.round(b * DIV).toInt()) return n to false
            if (dur == Math.round(b * 1.5 * DIV).toInt()) return n to true
        }
        return null
    }

    private val sharpSteps = arrayOf("C" to 0, "C" to 1, "D" to 0, "D" to 1, "E" to 0, "F" to 0, "F" to 1, "G" to 0, "G" to 1, "A" to 0, "A" to 1, "B" to 0)
    private val flatSteps = arrayOf("C" to 0, "D" to -1, "D" to 0, "E" to -1, "E" to 0, "F" to 0, "G" to -1, "G" to 0, "A" to -1, "A" to 0, "B" to -1, "B" to 0)

    private fun pitch(midi: Int, fifths: Int): String {
        val (step, alter) = (if (fifths < 0) flatSteps else sharpSteps)[Math.floorMod(midi, 12)]
        val oct = Math.floorDiv(midi, 12) - 1
        return "<pitch><step>$step</step>" + (if (alter != 0) "<alter>$alter</alter>" else "") + "<octave>$oct</octave></pitch>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
