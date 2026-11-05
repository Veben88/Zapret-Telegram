package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.notation.*
import ru.partitrainer.core.structure.MeasureMark

class EngravingTest {
    @Test fun spelling() {
        assertEquals(Pitch(0, 0, 4), Engraving.spell(60, 0))        // C4
        assertEquals(Pitch(3, 1, 4), Engraving.spell(66, 0))        // F#4
        assertEquals(Pitch(6, -1, 4), Engraving.spell(70, -2))      // Bb4 в тональности с бемолями
        assertEquals(Pitch(0, 0, 4), Engraving.spell(72 - 12, 3))
    }

    @Test fun keyAlterAndSignature() {
        assertEquals(1, Engraving.keyAlter(3, 1))   // F# в соль мажоре
        assertEquals(0, Engraving.keyAlter(0, 1))
        assertEquals(-1, Engraving.keyAlter(6, -1)) // Bb в фа мажоре
        assertEquals(listOf(3, 0), Engraving.keySignature(2))
        assertEquals(listOf(6, 2, 5), Engraving.keySignature(-3))
    }

    @Test fun staffPositions() {
        assertEquals(0, Engraving.staffPos(Pitch(2, 0, 4), Clef.TREBLE))   // E4 — нижняя линия
    }

    @Test fun trebleTopLineIsF5() {
        assertEquals(8, Engraving.staffPos(Pitch(3, 0, 5), Clef.TREBLE))
        assertEquals(-2, Engraving.staffPos(Pitch(0, 0, 4), Clef.TREBLE))  // C4 — одна добавочная линейка под станом
        assertEquals(listOf(-2), Engraving.ledgerLines(-2))
        assertEquals(emptyList<Int>(), Engraving.ledgerLines(-1))   // ноту в промежутке под станом линейка не нужна
        assertEquals(listOf(10, 12), Engraving.ledgerLines(12))
        assertEquals(0, Engraving.staffPos(Pitch(4, 0, 2), Clef.BASS))      // G2 — нижняя линия баса
    }

    @Test fun durations() {
        assertEquals(Dur(2), Engraving.durationOf(1.0))
        assertEquals(Dur(1, 1), Engraving.durationOf(3.0))
        assertEquals(Dur(3), Engraving.durationOf(0.5))
        assertEquals(Dur(0), Engraving.durationOf(4.0))
        assertEquals(Dur(2, 1), Engraving.durationOf(1.5))
        assertEquals(Dur(3, 0, true), Engraving.durationOf(1.0 / 3))
        assertEquals(Dur(4), Engraving.durationOf(0.25))
    }

    @Test fun beams() {
        val s = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0)
        val b = listOf(0.5, 0.5, 0.5, 0.5, 1.0, 1.0)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), Engraving.beamGroups(s, b))
        // пауза между нотами разрывает группу
        assertEquals(emptyList<List<Int>>(), Engraving.beamGroups(listOf(0.0, 0.75), listOf(0.25, 0.25)))
    }

    @Test fun playMapWithRepeat() {
        val marks = listOf(MeasureMark(0.0, 4.0), MeasureMark(4.0, 4.0), MeasureMark(8.0, 4.0))
        val pm = PlayMap(marks, listOf(0, 1, 0, 1, 2))
        assertTrue(pm.expanded)
        assertEquals(1.0, pm.originalBeat(1.0), 1e-9)
        assertEquals(1.0, pm.originalBeat(9.0), 1e-9)      // второй проход такта 1
        assertEquals(0, pm.originalMeasure(8.5))
        assertEquals(2, pm.originalMeasure(17.0))
        assertEquals(1.0, pm.expandedBeat(1.0), 1e-9)                      // первое вхождение
        assertEquals(16.0, pm.expandedBeat(8.0), 1e-9)                     // такт 3 звучит пятым
        val plain = PlayMap(marks, null)
        assertFalse(plain.expanded)
        assertEquals(5.0, plain.originalBeat(5.0), 1e-9)
    }

    @Test fun packing() {
        val lines = SystemPacker.pack(List(5) { 100.0 }, 40.0, 440.0)
        assertEquals(listOf(4, 1), lines.map { it.toExclusive - it.from })
        assertEquals(400.0, lines[0].widths.sum(), 1e-6)     // растянуто на всю строку
        assertEquals(100.0, lines[1].widths.sum(), 1e-6)     // последняя строка мало заполнена — не растягивается
        assertEquals(400.0, SystemPacker.pack(List(7) { 100.0 }, 40.0, 440.0)[1].widths.sum(), 1e-6) // заполнена достаточно — растягивается
        // слишком широкий такт всё равно получает свою строку
        assertEquals(1, SystemPacker.pack(listOf(900.0), 40.0, 440.0).size)
    }
}
