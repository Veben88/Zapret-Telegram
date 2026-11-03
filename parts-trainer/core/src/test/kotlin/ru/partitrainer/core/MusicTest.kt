package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.music.*

class MusicTest {
    @Test fun parseNames() {
        assertEquals(60, NoteNames.parse("C4"))
        assertEquals(66, NoteNames.parse("F#4"))
        assertEquals(70, NoteNames.parse("Bb4"))
        assertEquals(71, NoteNames.parse("B4"))
        assertEquals(67, NoteNames.parse("соль4"))
        assertEquals(61, NoteNames.parse("до#4"))
        assertEquals(70, NoteNames.parse("Си♭4"))
        assertEquals(62, NoteNames.parse("Ре4"))
        assertNull(NoteNames.parse("H4"))
        assertNull(NoteNames.parse(""))
    }

    @Test fun formatNames() {
        assertEquals("соль4", NoteNames.nameWithOctave(67, Notation.SOLFEGE))
        assertEquals("F#4", NoteNames.nameWithOctave(66, Notation.LATIN))
        assertEquals("до♯5", NoteNames.nameWithOctave(73, Notation.SOLFEGE))
        assertEquals(4, NoteNames.octave(60))
        assertEquals(3, NoteNames.octave(59))
        assertEquals(-1, NoteNames.octave(0))
    }

    @Test fun dsl() {
        val ev = NoteDsl.parseEvents("B4:1 r:.5 F#4:3")
        assertEquals(3, ev.size)
        assertNull(ev[1].midi)
        assertEquals(0.5, ev[1].beats, 0.0)
        val (n, end) = NoteDsl.build(ev, 0.0, 4.0)
        assertEquals(2, n.size)
        assertEquals(1.5, n[1].startBeat, 0.0)
        assertEquals(4.5, end, 0.0)
        assertEquals(1, n[1].measure)
        assertEquals(2, NoteDsl.measureOf(4.0, 4.0))
    }

    @Test fun dslErrors() {
        assertThrows(IllegalArgumentException::class.java) { NoteDsl.parseEvents("B4") }
        assertThrows(IllegalArgumentException::class.java) { NoteDsl.parseEvents("Z9:1") }
    }

    @Test fun builtinSong() {
        val b = BuiltinSong.build()
        val v = b.score.voices.single()
        assertEquals(13, BuiltinSong.lines.size)
        assertEquals(71, v.notes.first().midi) // B4
        // последняя нота — Bb4:6
        assertEquals(70, v.notes.last().midi)
        assertEquals(6.0, v.notes.last().beats, 0.0)
        // D5:15 в 6-й строке куплета
        assertTrue(v.notes.any { it.midi == 74 && it.beats == 15.0 })
        // «Финал»: G4:5 A4:3 A4:1 Bb4:6
        assertEquals(listOf(67, 69, 69, 70), v.notes.takeLast(4).map { it.midi })
        assertEquals(100, b.score.tempo)
        assertEquals(1, b.score.keyFifths)
        // ноты идут подряд без наложений
        v.notes.zipWithNext().forEach { (a, c) -> assertTrue(c.startBeat >= a.endBeat - 1e-9) }
        // Строки: 13, первая строка начинается с индекса 0 (в lineStarts его нет)
        assertEquals(12, b.lineStarts.size)
    }

    @Test fun transposeAndRange() {
        val n = listOf(Note(60, 0.0, 1.0, 1), Note(72, 1.0, 1.0, 1))
        assertEquals(listOf(48, 60), Transpose.notes(n, -12).map { it.midi })
        assertEquals(listOf(72, 84), Transpose.notes(n, 99).map { it.midi }) // сдвиг ограничен до +12
        assertTrue(Ranges.outOfRange(Note(85, 0.0, 1.0, 1), VoiceKind.SOPRANO))
        assertFalse(Ranges.outOfRange(Note(60, 0.0, 1.0, 1), VoiceKind.SOPRANO))
        assertNull(Ranges.of(VoiceKind.MELODY))
        assertEquals(MidiRange(60, 72), Ranges.actual(n))
    }

    @Test fun phrasesByRest() {
        val notes = listOf(
            Note(60, 0.0, 1.0, 1), Note(62, 1.0, 1.0, 1),
            Note(64, 4.0, 1.0, 2), Note(65, 5.0, 1.0, 2),   // пауза 2 доли
        )
        val ph = PhraseSplitter.split(notes, 4.0)
        assertEquals(listOf(0 to 2, 2 to 4), ph.map { it.from to it.to })
    }

    @Test fun phrasesByGridAndLines() {
        val notes = (0 until 16).map { Note(60, it.toDouble(), 1.0, it / 4 + 1) }
        assertEquals(2, PhraseSplitter.split(notes, 4.0, 2).size)   // по 2 такта
        assertEquals(4, PhraseSplitter.split(notes, 4.0, 1).size)
        assertEquals(1, PhraseSplitter.split(notes, 4.0, 4).size)
        val byLines = PhraseSplitter.split(notes, 4.0, 2, setOf(3, 10))
        assertEquals(listOf(0, 3, 10), byLines.map { it.from })
    }
}

class SpellingTest {
    @Test fun flatsInFlatKeys() {
        assertEquals("ля♭4", NoteNames.nameWithOctave(68, Notation.SOLFEGE, true))
        assertEquals("Ab4", NoteNames.nameWithOctave(68, Notation.LATIN, true))
        assertEquals("G#4", NoteNames.nameWithOctave(68, Notation.LATIN, false))
        assertEquals("до4", NoteNames.nameWithOctave(60, Notation.SOLFEGE, true))
    }
}
