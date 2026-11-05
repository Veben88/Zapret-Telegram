package ru.partitrainer.core.io

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.structure.MeasureMark
import ru.partitrainer.core.voices.VoiceClassifier

/** Собственный разбор Standard MIDI File (форматы 0 и 1, PPQ-разрешение). */
object MidiParser {
    /** Сетка квантования: 1/24 доли (шестнадцатые триоли). */
    const val GRID = 24.0

    private class Reader(val b: ByteArray, var pos: Int = 0) {
        fun u8(): Int { if (pos >= b.size) throw ImportException("Неожиданный конец MIDI-файла"); return b[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun vlq(): Long { var v = 0L; repeat(4) { val c = u8(); v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v }; throw ImportException("Неверная длина VLQ") }
        fun str(n: Int): String { val s = String(b, pos, n.coerceAtMost(b.size - pos), Charsets.UTF_8); pos += n; return s }
    }

    private data class Ev(val tick: Long, val kind: Int, val ch: Int, val a: Int, val b: Int)

    fun parse(bytes: ByteArray): ImportedPiece {
        val r = Reader(bytes)
        if (bytes.size < 14 || r.str(4) != "MThd") throw ImportException("Это не MIDI-файл")
        val hdrLen = r.u32().toInt()
        val format = r.u16()
        val nTracks = r.u16()
        val division = r.u16()
        r.pos = 8 + hdrLen
        if (division and 0x8000 != 0) throw ImportException("SMPTE-тайминг в MIDI не поддерживается")
        if (format > 1) throw ImportException("MIDI формата $format не поддерживается")
        val tpq = division.coerceAtLeast(1)

        var tempoMicro: Long? = null
        var timeNum = 4; var timeDen = 4; var timeSet = false
        var fifths = 0; var keySet = false
        var title: String? = null
        val parts = ArrayList<RawPart>()

        for (t in 0 until nTracks) {
            if (r.pos + 8 > bytes.size) break
            val id = r.str(4)
            val len = r.u32().toInt()
            val end = (r.pos + len).coerceAtMost(bytes.size)
            if (id != "MTrk") { r.pos = end; continue }
            var tick = 0L
            var status = 0
            var trackName = ""
            var instrument = ""
            val ons = HashMap<Int, Pair<Long, Int>>() // ch*128+key -> (tick, velocity)
            val rawNotes = ArrayList<Triple<Int, Long, Long>>() // key, start, end ; ch in map
            val chOf = ArrayList<Int>()
            val lyrics = ArrayList<Pair<Long, String>>()
            while (r.pos < end) {
                tick += r.vlq()
                var s = r.u8()
                if (s < 0x80) { r.pos--; s = status } else if (s < 0xF0) status = s
                when {
                    s == 0xFF -> {
                        val type = r.u8(); val l = r.vlq().toInt()
                        val dataStart = r.pos
                        when (type) {
                            0x03 -> trackName = r.str(l).trim()
                            0x04 -> instrument = r.str(l).trim()
                            0x05 -> lyrics += tick to r.str(l)
                            0x51 -> if (l == 3 && tempoMicro == null) tempoMicro = (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong()
                            0x58 -> if (l >= 2 && !timeSet) { timeNum = r.u8(); timeDen = 1 shl r.u8(); timeSet = true }
                            0x59 -> if (l >= 1 && !keySet) { fifths = r.u8().toByte().toInt(); keySet = true }
                        }
                        r.pos = dataStart + l
                        if (type == 0x2F) { r.pos = end }
                    }
                    s == 0xF0 || s == 0xF7 -> { val l = r.vlq().toInt(); r.pos += l }
                    else -> {
                        val ch = s and 0x0F
                        val cmd = s and 0xF0
                        when (cmd) {
                            0x90, 0x80 -> {
                                val key = r.u8(); val vel = r.u8()
                                val k = ch * 128 + key
                                if (cmd == 0x90 && vel > 0) {
                                    ons[k]?.let { (st, _) -> if (tick > st) { rawNotes += Triple(key, st, tick); chOf += ch } }
                                    ons[k] = tick to vel
                                } else {
                                    ons.remove(k)?.let { (st, _) -> rawNotes += Triple(key, st, tick); chOf += ch }
                                }
                            }
                            0xA0, 0xB0, 0xE0 -> { r.u8(); r.u8() }
                            0xC0, 0xD0 -> r.u8()
                            else -> throw ImportException("Неизвестное MIDI-событие")
                        }
                    }
                }
            }
            r.pos = end
            if (trackName.isNotEmpty() && title == null && t == 0 && format == 1 && rawNotes.isEmpty()) title = trackName
            // Разбиваем по каналам (кроме ударных, канал 10).
            val byCh = rawNotes.indices.groupBy { chOf[it] }.filterKeys { it != 9 }
            for ((ch, idxs) in byCh) {
                val notes = idxs.map { rawNotes[it] }.sortedBy { it.second }.map { (key, st, en) ->
                    val s0 = quant(st / tpq.toDouble()); val e0 = quant(en / tpq.toDouble())
                    RawNote(Note(key, s0, (e0 - s0).coerceAtLeast(1 / GRID), 1, lyricAt(lyrics, st)))
                }
                if (notes.isNotEmpty()) {
                    val nm = trackName.ifBlank { if (byCh.size > 1) "Канал ${ch + 1}" else "Трек ${parts.size + 1}" }
                    parts += RawPart(nm, instrument, notes)
                }
            }
        }
        if (parts.isEmpty()) throw ImportException("В MIDI-файле нет нот")
        val bpmm = timeNum * 4.0 / timeDen
        // Нумерация тактов.
        val fixed = parts.map { p ->
            p.copy(notes = p.notes.map { it.copy(note = it.note.copy(measure = Math.floor(it.note.startBeat / bpmm + 1e-9).toInt() + 1)) })
        }
        val voices = VoiceClassifier.classify(fixed)
        val tempo = tempoMicro?.let { Math.round(60_000_000.0 / it).toInt() }
        val total = fixed.maxOf { p -> p.notes.maxOf { it.note.endBeat } }
        val nMeasures = Math.ceil(total / bpmm - 1e-9).toInt().coerceAtLeast(1)
        val marks = List(nMeasures) { MeasureMark(it * bpmm, bpmm) }
        return ImportedPiece(Score(title, fifths, timeNum, timeDen, tempo, voices), marks)
    }

    private fun quant(beats: Double) = Math.round(beats * GRID) / GRID

    private fun lyricAt(lyrics: List<Pair<Long, String>>, tick: Long): String? =
        lyrics.firstOrNull { it.first == tick }?.second?.trim()?.ifEmpty { null }
}
