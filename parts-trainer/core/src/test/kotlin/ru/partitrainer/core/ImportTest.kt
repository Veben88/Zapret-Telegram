package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.io.*
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.structure.Jump
import ru.partitrainer.core.structure.Repeats
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun res(name: String) = object {}.javaClass.getResourceAsStream("/$name")!!.readBytes()

class MusicXmlTest {
    @Test fun simple() {
        val piece = MusicXmlParser.parse(res("simple.musicxml").inputStream())
        val s = piece.score
        assertEquals("Тест", s.title)
        assertEquals(1, s.keyFifths)
        assertEquals(100, s.tempo)
        assertEquals(2, s.voices.size)
        val sop = s.voices.first { it.kind == VoiceKind.SOPRANO }
        val bass = s.voices.first { it.kind == VoiceKind.BASS }
        // Лига G4 (2 доли) + G4 (1 доля) слита в одну ноту на 3 доли
        assertEquals(listOf(67, 66, 69), sop.notes.map { it.midi })
        assertEquals(3.0, sop.notes[0].beats, 1e-9)
        assertEquals("ля-", sop.notes[0].lyric)
        // Пауза 1 доля: следующий такт начинается с 4-й доли — F#4 в такте 2 (форшлаг пропущен)
        assertEquals(4.0, sop.notes[1].startBeat, 1e-9)
        assertEquals(2, sop.notes[1].measure)
        assertEquals(listOf(48, 50), bass.notes.map { it.midi })
        assertEquals(2, piece.marks.size)
        assertEquals(4.0, piece.marks[1].startBeat, 1e-9)
    }

    @Test fun dsAlCodaWithVoltas() {
        val piece = MusicXmlParser.parse(res("ds_coda_voltas.musicxml").inputStream())
        val m = piece.marks
        assertEquals(6, m.size)
        assertTrue(m[0].segno); assertTrue(m[1].repeatStart)
        assertTrue(m[2].repeatEnd); assertEquals(listOf(1), m[2].volta)
        assertEquals(listOf(2), m[3].volta); assertEquals(listOf(2), m[4].volta)
        assertTrue(m[3].toCoda); assertEquals(Jump.DS_AL_CODA, m[4].jump); assertTrue(m[5].coda)
        val e = Repeats.expandRepeats(m)
        assertFalse(e.ambiguous)
        assertEquals(listOf(0, 1, 2, 1, 3, 4, 0, 1, 3, 5), e.order)
        // Применение к нотам
        val x = Repeats.applyToScore(piece.score, m, e.order).voices.single().notes
        assertEquals(10, x.size)
        assertEquals(listOf(60, 62, 64, 62, 65, 67, 60, 62, 65, 69), x.map { it.midi })
        assertEquals(36.0, x[9].startBeat, 1e-9)
    }

    @Test fun mxl() {
        val xml = res("simple.musicxml")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="score/main.xml"/></rootfiles></container>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("score/main.xml")); z.write(xml); z.closeEntry()
        }
        val piece = MusicXmlParser.parseAuto(bos.toByteArray())
        assertEquals(2, piece.score.voices.size)
    }

    @Test fun badXml() {
        assertThrows(ImportException::class.java) { MusicXmlParser.parse("<foo".byteInputStream()) }
        assertThrows(ImportException::class.java) { MusicXmlParser.parse("<score-partwise></score-partwise>".byteInputStream()) }
    }

    @Test fun exportRoundTrip() {
        val notes = listOf(
            Note(67, 0.0, 6.0, 1, "ля-"),   // длиннее такта: делится лигой
            Note(66, 6.0, 1.5, 2),
            Note(70, 8.0, 2.0, 3),
        )
        val sc = Score("Экспорт", 1, 4, 4, 100, listOf(Voice("a", "Тест", VoiceKind.SOPRANO, notes)))
        val xml = MusicXmlWriter.write(sc)
        val back = MusicXmlParser.parse(xml.byteInputStream()).score.voices.single()
        assertEquals(notes.map { it.midi }, back.notes.map { it.midi })
        assertEquals(notes.map { it.beats }, back.notes.map { it.beats })
        assertEquals(notes.map { it.startBeat }, back.notes.map { it.startBeat })
        assertEquals("ля-", back.notes[0].lyric)
    }
}

class MidiTest {
    @Test fun roundTrip() {
        val v1 = Voice("1", "Сопрано", VoiceKind.SOPRANO, listOf(Note(72, 0.0, 1.0, 1), Note(74, 1.0, 0.5, 1), Note(76, 2.0, 2.0, 1)))
        val v2 = Voice("2", "Бас", VoiceKind.BASS, listOf(Note(48, 0.0, 4.0, 1), Note(43, 4.0, 4.0, 2)))
        val bytes = MidiWriter.write("Песня", listOf(v1, v2), 90, 3, 4)
        val piece = MidiParser.parse(bytes)
        val s = piece.score
        assertEquals(90, s.tempo)
        assertEquals(3, s.timeNum); assertEquals(4, s.timeDen)
        assertEquals(2, s.voices.size)
        val sop = s.voices.first { it.kind == VoiceKind.SOPRANO }
        assertEquals(listOf(72, 74, 76), sop.notes.map { it.midi })
        assertEquals(listOf(0.0, 1.0, 2.0), sop.notes.map { it.startBeat })
        assertEquals(0.5, sop.notes[1].beats, 1e-9)
        val bass = s.voices.first { it.kind == VoiceKind.BASS }
        assertEquals(listOf(48, 43), bass.notes.map { it.midi })
        assertEquals(2, bass.notes[1].measure) // 3/4: нота на 4-й доле → такт 2
    }

    @Test fun runningStatusAndTempoMeta() {
        // Вручную: формат 0, PPQ 96, running status, tempo 500000 (=120)
        val trk = byteArrayOf(
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20,
            0x00, 0x90.toByte(), 60, 100,
            0x60, 60, 0,              // running status: note-on vel 0 = off, через 96 тиков
            0x00, 62, 100,
            0x60, 62, 0,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val bos = ByteArrayOutputStream()
        bos.write("MThd".toByteArray()); bos.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0, 96))
        bos.write("MTrk".toByteArray()); bos.write(byteArrayOf(0, 0, 0, trk.size.toByte())); bos.write(trk)
        val p = MidiParser.parse(bos.toByteArray())
        assertEquals(120, p.score.tempo)
        val n = p.score.voices.single().notes
        assertEquals(listOf(60, 62), n.map { it.midi })
        assertEquals(1.0, n[0].beats, 1e-9)
        assertEquals(1.0, n[1].startBeat, 1e-9)
    }

    @Test fun errors() {
        assertThrows(ImportException::class.java) { MidiParser.parse("nope".toByteArray()) }
        assertThrows(ImportException::class.java) { MidiParser.parse(ByteArray(20)) }
    }
}
