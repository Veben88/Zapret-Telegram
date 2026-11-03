package ru.partitrainer.core.io

import org.xmlpull.v1.XmlPullParser
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.structure.Jump
import ru.partitrainer.core.structure.MeasureMark
import ru.partitrainer.core.voices.VoiceClassifier
import java.io.InputStream
import java.util.zip.ZipInputStream

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Разбор MusicXML (score-partwise) на XmlPullParser. Залигованные ноты сливаются. */
object MusicXmlParser {

    fun parse(input: InputStream): ImportedPiece {
        try {
            return Impl(newParser(input)).run()
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Не удалось прочитать MusicXML: ${e.message}", e)
        }
    }

    /** Сжатый .mxl: ZIP, в META-INF/container.xml указан корневой файл. */
    fun parseMxl(input: InputStream): ImportedPiece {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = zip.readBytes().also { check(it.size < 64_000_000) }
            }
        }
        val container = files["META-INF/container.xml"]
        var root: String? = null
        if (container != null) {
            val p = newParser(container.inputStream())
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                    root = p.getAttributeValue(null, "full-path"); break
                }
            }
        }
        val name = root ?: files.keys.firstOrNull { !it.startsWith("META-INF") && (it.endsWith(".xml") || it.endsWith(".musicxml")) }
            ?: throw ImportException("В .mxl не найден файл с нотами")
        val bytes = files[name] ?: throw ImportException("В .mxl нет файла $name")
        return parse(bytes.inputStream())
    }

    /** Определяет формат по началу данных (ZIP «PK» или XML). */
    fun parseAuto(bytes: ByteArray): ImportedPiece =
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) parseMxl(bytes.inputStream())
        else parse(bytes.inputStream())

    private class Impl(val p: XmlPullParser) {
        var title: String? = null
        var fifths = 0
        var timeNum = 4
        var timeDen = 4
        var tempo: Int? = null
        var timeSet = false

        class PartInfo(val id: String, var name: String = "", var instrument: String = "")
        val partInfos = LinkedHashMap<String, PartInfo>()
        val parts = ArrayList<RawPart>()
        var marks: List<MeasureMark> = emptyList()

        fun run(): ImportedPiece {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "score-timewise" -> throw ImportException("score-timewise не поддерживается")
                    "movement-title" -> title = p.readText().ifBlank { title }
                    "work-title" -> if (title == null) title = p.readText().ifBlank { null }
                    "score-part" -> scorePart()
                    "part" -> part()
                }
            }
            if (parts.isEmpty()) throw ImportException("В файле нет партий")
            val voices = VoiceClassifier.classify(parts)
            val score = Score(title, fifths, timeNum, timeDen, tempo, voices)
            return ImportedPiece(score, marks)
        }

        fun scorePart() {
            val info = PartInfo(p.getAttributeValue(null, "id") ?: "P${partInfos.size + 1}")
            val depth = p.depth
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "part-name" -> info.name = p.readText()
                    "instrument-name" -> if (info.instrument.isEmpty()) info.instrument = p.readText()
                }
            }
            partInfos[info.id] = info
        }

        fun part() {
            val id = p.getAttributeValue(null, "id") ?: ""
            val info = partInfos[id] ?: PartInfo(id)
            val notes = ArrayList<RawNote>()
            val open = HashMap<String, Int>() // ключ staff|voice|midi -> индекс ноты
            val first = parts.isEmpty()
            val measureMarks = ArrayList<MeasureMark>()

            var divisions = 1
            var curTimeNum = timeNum
            var curTimeDen = timeDen
            var measureStart = 0.0
            var voltaNumbers: List<Int> = emptyList()
            var measureIdx = 0
            val partDepth = p.depth

            while (!(p.next() == XmlPullParser.END_TAG && p.depth == partDepth)) {
                if (p.eventType != XmlPullParser.START_TAG || p.name != "measure") continue
                measureIdx++
                val implicit = p.getAttributeValue(null, "implicit") == "yes"
                val mDepth = p.depth
                var cursor = 0.0     // в долях
                var maxCursor = 0.0
                var lastChordStart = 0.0
                var repeatStart = false; var repeatEnd = false; var times = 2
                var segno = false; var coda = false; var toCoda = false; var fine = false
                var jump: Jump? = null
                var voltaStop = false
                val mMarkWords = ArrayList<String>()

                while (!(p.next() == XmlPullParser.END_TAG && p.depth == mDepth)) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "attributes" -> {
                            val d = p.depth
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == d)) {
                                if (p.eventType != XmlPullParser.START_TAG) continue
                                when (p.name) {
                                    "divisions" -> divisions = p.readText().toIntOrNull()?.coerceAtLeast(1) ?: divisions
                                    "key" -> {} // заходим внутрь: нужен только <fifths>
                                    "fifths" -> if (first && measureIdx == 1) fifths = p.readText().toIntOrNull() ?: 0 else p.skipElement()
                                    "time" -> {
                                        var b = 0; var bt = 0
                                        val td = p.depth
                                        while (!(p.next() == XmlPullParser.END_TAG && p.depth == td)) {
                                            if (p.eventType != XmlPullParser.START_TAG) continue
                                            when (p.name) {
                                                "beats" -> b = p.readText().substringBefore('+').toIntOrNull() ?: 0
                                                "beat-type" -> bt = p.readText().toIntOrNull() ?: 0
                                                else -> p.skipElement()
                                            }
                                        }
                                        if (b > 0 && bt > 0) {
                                            curTimeNum = b; curTimeDen = bt
                                            if (!timeSet) { timeNum = b; timeDen = bt; timeSet = true }
                                        }
                                    }
                                    else -> p.skipElement()
                                }
                            }
                        }
                        "barline" -> {
                            val d = p.depth
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == d)) {
                                if (p.eventType != XmlPullParser.START_TAG) continue
                                when (p.name) {
                                    "repeat" -> {
                                        val dir = p.getAttributeValue(null, "direction")
                                        if (dir == "forward") repeatStart = true
                                        if (dir == "backward") {
                                            repeatEnd = true
                                            p.getAttributeValue(null, "times")?.toIntOrNull()?.let { times = it.coerceAtLeast(2) }
                                        }
                                        p.skipElement()
                                    }
                                    "ending" -> {
                                        val type = p.getAttributeValue(null, "type")
                                        val nums = (p.getAttributeValue(null, "number") ?: "1")
                                            .split(',', ' ').mapNotNull { it.trim().trimEnd('.').toIntOrNull() }
                                        if (type == "start") { voltaNumbers = nums }
                                        else voltaStop = true
                                        p.skipElement()
                                    }
                                    "segno" -> { segno = true; p.skipElement() }
                                    "coda" -> { coda = true; p.skipElement() }
                                    else -> p.skipElement()
                                }
                            }
                        }
                        "direction" -> {
                            val d = p.depth
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == d)) {
                                if (p.eventType != XmlPullParser.START_TAG) continue
                                when (p.name) {
                                    "direction-type" -> {} // заходим внутрь
                                    "segno" -> { segno = true; p.skipElement() }
                                    "coda" -> { coda = true; p.skipElement() }
                                    "words" -> { mMarkWords += p.readText() }
                                    "metronome" -> {
                                        val md = p.depth
                                        var unit = ""; var pm = 0.0; var dot = false
                                        while (!(p.next() == XmlPullParser.END_TAG && p.depth == md)) {
                                            if (p.eventType != XmlPullParser.START_TAG) continue
                                            when (p.name) {
                                                "beat-unit" -> unit = p.readText()
                                                "beat-unit-dot" -> { dot = true; p.skipElement() }
                                                "per-minute" -> pm = p.readText().toDoubleOrNull() ?: 0.0
                                                else -> p.skipElement()
                                            }
                                        }
                                        if (first && tempo == null && pm > 0) {
                                            val q = when (unit) { "half" -> 2.0; "quarter" -> 1.0; "eighth" -> 0.5; "whole" -> 4.0; else -> 1.0 } * (if (dot) 1.5 else 1.0)
                                            tempo = (pm * q).toInt()
                                        }
                                    }
                                    "sound" -> {
                                        soundAttrs(
                                            { a -> p.getAttributeValue(null, a) },
                                            first,
                                        ).let { s ->
                                            if (s.segno) segno = true
                                            if (s.coda) coda = true
                                            if (s.toCoda) toCoda = true
                                            if (s.fine) fine = true
                                            if (s.jump != null) jump = s.jump
                                        }
                                        p.skipElement()
                                    }
                                    else -> p.skipElement()
                                }
                            }
                        }
                        "sound" -> {
                            val s = soundAttrs({ a -> p.getAttributeValue(null, a) }, first)
                            if (s.segno) segno = true; if (s.coda) coda = true
                            if (s.toCoda) toCoda = true; if (s.fine) fine = true
                            if (s.jump != null) jump = s.jump
                            p.skipElement()
                        }
                        "backup" -> { cursor -= durationOf(divisions); if (cursor < 0) cursor = 0.0 }
                        "forward" -> { cursor += durationOf(divisions); maxCursor = maxOf(maxCursor, cursor) }
                        "note" -> {
                            val nd = noteElement(divisions)
                            if (nd.grace) {
                                // форшлаги пропускаем
                            } else {
                                val start = if (nd.chord) lastChordStart else cursor
                                if (!nd.chord) lastChordStart = cursor
                                val nm = nd.midi
                                if (!nd.rest && nm != null && !nd.unpitched) {
                                    val key = "${nd.staff}|${nd.voice}|$nm"
                                    val oi = open[key]
                                    if (nd.tieStop && oi != null) {
                                        val prev = notes[oi]
                                        notes[oi] = prev.copy(note = prev.note.copy(beats = prev.note.beats + nd.beats))
                                        if (nd.tieStart) { /* остаётся открытой */ } else open.remove(key)
                                    } else {
                                        notes += RawNote(
                                            Note(nm, measureStart + start, nd.beats, measureIdx, nd.lyric),
                                            nd.staff, nd.voice,
                                        )
                                        if (nd.tieStart) open[key] = notes.lastIndex else open.remove(key)
                                    }
                                }
                                if (!nd.chord) { cursor += nd.beats; maxCursor = maxOf(maxCursor, cursor) }
                            }
                        }
                        else -> p.skipElement()
                    }
                }

                // Надписи D.S./D.C./Fine/To Coda
                for (w in mMarkWords) {
                    val s = classifyWords(w)
                    if (s.toCoda) toCoda = true; if (s.fine) fine = true
                    val sj = s.jump
                    if (sj != null && (jump == null || jump!!.toStart == sj.toStart)) jump = sj
                    if (s.segno) segno = true; if (s.coda) coda = true
                }

                val nominal = curTimeNum * 4.0 / curTimeDen
                val length = if (implicit || (maxCursor > 0 && maxCursor < nominal && measureIdx == 1)) maxCursor.coerceAtLeast(0.25)
                else maxOf(nominal, maxCursor)
                val volta = if (voltaNumbers.isNotEmpty()) voltaNumbers else emptyList()
                measureMarks += MeasureMark(
                    startBeat = measureStart, lengthBeats = length,
                    repeatStart = repeatStart, repeatEnd = repeatEnd, repeatTimes = times,
                    volta = volta, segno = segno, coda = coda, toCoda = toCoda, fine = fine, jump = jump,
                )
                if (voltaStop) voltaNumbers = emptyList()
                measureStart += length
            }
            if (first) marks = measureMarks
            parts += RawPart(info.name, info.instrument, notes)
        }

        fun durationOf(divisions: Int): Double {
            val d = p.depth
            var v = 0.0
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == d)) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    if (p.name == "duration") v = (p.readText().toDoubleOrNull() ?: 0.0) / divisions else p.skipElement()
                }
            }
            return v
        }

        class NoteData {
            var rest = false; var chord = false; var grace = false; var unpitched = false
            var midi: Int? = null; var beats = 0.0
            var tieStart = false; var tieStop = false
            var staff = 1; var voice = "1"; var lyric: String? = null
        }

        fun noteElement(divisions: Int): NoteData {
            val n = NoteData()
            var step = ""; var alter = 0.0; var octave = 4
            val d = p.depth
            while (!(p.next() == XmlPullParser.END_TAG && p.depth == d)) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "rest" -> { n.rest = true; p.skipElement() }
                    "chord" -> { n.chord = true; p.skipElement() }
                    "grace" -> { n.grace = true; p.skipElement() }
                    "unpitched" -> { n.unpitched = true; p.skipElement() }
                    "pitch" -> {
                        val pd = p.depth
                        while (!(p.next() == XmlPullParser.END_TAG && p.depth == pd)) {
                            if (p.eventType != XmlPullParser.START_TAG) continue
                            when (p.name) {
                                "step" -> step = p.readText()
                                "alter" -> alter = p.readText().toDoubleOrNull() ?: 0.0
                                "octave" -> octave = p.readText().toIntOrNull() ?: 4
                                else -> p.skipElement()
                            }
                        }
                        val pc = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)[step]
                        if (pc != null) n.midi = ((octave + 1) * 12 + pc + Math.round(alter).toInt()).coerceIn(0, 127)
                    }
                    "duration" -> n.beats = (p.readText().toDoubleOrNull() ?: 0.0) / divisions
                    "tie" -> {
                        when (p.getAttributeValue(null, "type")) { "start" -> n.tieStart = true; "stop" -> n.tieStop = true }
                        p.skipElement()
                    }
                    "voice" -> n.voice = p.readText().ifBlank { "1" }
                    "staff" -> n.staff = p.readText().toIntOrNull() ?: 1
                    "lyric" -> {
                        val ld = p.depth
                        var text = ""; var syl = ""
                        while (!(p.next() == XmlPullParser.END_TAG && p.depth == ld)) {
                            if (p.eventType != XmlPullParser.START_TAG) continue
                            when (p.name) {
                                "text" -> text = p.readText()
                                "syllabic" -> syl = p.readText()
                                else -> p.skipElement()
                            }
                        }
                        if (n.lyric == null && text.isNotEmpty()) n.lyric = if (syl == "begin" || syl == "middle") "$text-" else text
                    }
                    else -> p.skipElement()
                }
            }
            return n
        }
    }

    internal class Sound(
        val segno: Boolean = false, val coda: Boolean = false, val toCoda: Boolean = false,
        val fine: Boolean = false, val jump: Jump? = null,
    )

    private fun soundAttrs(get: (String) -> String?, first: Boolean): Sound {
        val dacapo = get("dacapo") == "yes"
        val dalsegno = get("dalsegno") != null
        val tocoda = get("tocoda") != null
        val fineAttr = get("fine") != null
        // «al Coda»/«al Fine» уточняется по наличию отметок; тип перехода без уточнения.
        val jump = when {
            dacapo -> Jump.DC
            dalsegno -> Jump.DS
            else -> null
        }
        return Sound(
            segno = get("segno") != null, coda = get("coda") != null && get("coda") != "",
            toCoda = tocoda, fine = fineAttr, jump = jump,
        )
    }

    /** Разбор текстовых надписей над нотоносцем. */
    internal fun classifyWords(raw: String): Sound {
        val w = raw.lowercase().replace(Regex("[\\s.]+"), " ").trim()
        if (w.isEmpty()) return Sound()
        val alCoda = "al coda" in w || "al 𝄌" in w
        val alFine = "al fine" in w
        return when {
            w.startsWith("d c") || w.startsWith("da capo") -> Sound(jump = if (alCoda) Jump.DC_AL_CODA else if (alFine) Jump.DC_AL_FINE else Jump.DC)
            w.startsWith("d s") || w.startsWith("dal segno") -> Sound(jump = if (alCoda) Jump.DS_AL_CODA else if (alFine) Jump.DS_AL_FINE else Jump.DS)
            w.startsWith("to coda") -> Sound(toCoda = true)
            w == "fine" -> Sound(fine = true)
            else -> Sound()
        }
    }
}
