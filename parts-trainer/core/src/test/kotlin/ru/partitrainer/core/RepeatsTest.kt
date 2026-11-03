package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.structure.*

class RepeatsTest {
    private fun ms(n: Int, f: (Int, MeasureMark) -> MeasureMark = { _, m -> m }) =
        List(n) { f(it, MeasureMark(it * 4.0, 4.0)) }

    @Test fun noStructure() {
        assertEquals(listOf(0, 1, 2), Repeats.expandRepeats(ms(3)).order)
        assertFalse(Repeats.hasStructure(ms(3)))
    }

    @Test fun simpleRepeat() {
        val m = ms(4) { i, x -> when (i) { 1 -> x.copy(repeatStart = true); 2 -> x.copy(repeatEnd = true); else -> x } }
        assertEquals(listOf(0, 1, 2, 1, 2, 3), Repeats.expandRepeats(m).order)
    }

    @Test fun repeatWithoutStartGoesToBeginning() {
        val m = ms(3) { i, x -> if (i == 1) x.copy(repeatEnd = true) else x }
        assertEquals(listOf(0, 1, 0, 1, 2), Repeats.expandRepeats(m).order)
    }

    @Test fun voltas() {
        // |: A [1. B :| [2. C D
        val m = ms(4) { i, x -> when (i) {
            0 -> x.copy(repeatStart = true)
            1 -> x.copy(volta = listOf(1), repeatEnd = true)
            2 -> x.copy(volta = listOf(2))
            else -> x
        } }
        assertEquals(listOf(0, 1, 0, 2, 3), Repeats.expandRepeats(m).order)
    }

    @Test fun threeTimesWithThreeVoltas() {
        val m = ms(5) { i, x -> when (i) {
            0 -> x.copy(repeatStart = true, repeatTimes = 3)
            1 -> x.copy(volta = listOf(1, 2), repeatEnd = true, repeatTimes = 3)
            2 -> x.copy(volta = listOf(3))
            else -> x
        } }
        assertEquals(listOf(0, 1, 0, 1, 0, 2, 3, 4), Repeats.expandRepeats(m).order)
    }

    @Test fun daCapoAlFine() {
        val m = ms(4) { i, x -> when (i) { 1 -> x.copy(fine = true); 3 -> x.copy(jump = Jump.DC_AL_FINE); else -> x } }
        val e = Repeats.expandRepeats(m)
        assertEquals(listOf(0, 1, 2, 3, 0, 1), e.order)
        assertFalse(e.ambiguous)
    }

    @Test fun daCapoPlain() {
        val m = ms(3) { i, x -> if (i == 2) x.copy(jump = Jump.DC) else x }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), Repeats.expandRepeats(m).order)
    }

    @Test fun dalSegnoAlCodaWithVoltas() {
        // 0 segno; 1 |:; 2 [1 :|; 3 [2 toCoda; 4 [2 D.S. al Coda; 5 coda
        val m = ms(6) { i, x -> when (i) {
            0 -> x.copy(segno = true)
            1 -> x.copy(repeatStart = true)
            2 -> x.copy(volta = listOf(1), repeatEnd = true)
            3 -> x.copy(volta = listOf(2), toCoda = true)
            4 -> x.copy(volta = listOf(2), jump = Jump.DS_AL_CODA)
            else -> x.copy(coda = true)
        } }
        val e = Repeats.expandRepeats(m)
        assertFalse(e.ambiguous)
        assertEquals(listOf(0, 1, 2, 1, 3, 4, 0, 1, 3, 5), e.order)
    }

    @Test fun dsWithoutSegnoIsAmbiguous() {
        val m = ms(3) { i, x -> if (i == 2) x.copy(jump = Jump.DS) else x }
        val e = Repeats.expandRepeats(m)
        assertTrue(e.ambiguous)
        assertTrue(e.warnings.isNotEmpty())
        assertEquals(listOf(0, 1, 2), e.order) // не зацикливаемся
    }

    @Test fun toCodaWithoutCodaIsAmbiguous() {
        val m = ms(3) { i, x -> when (i) { 1 -> x.copy(toCoda = true); 2 -> x.copy(jump = Jump.DC_AL_CODA); else -> x } }
        assertTrue(Repeats.expandRepeats(m).ambiguous)
    }

    @Test fun shiftsNotes() {
        val marks = ms(2) { i, x -> if (i == 0) x.copy(repeatEnd = true) else x }
        val notes = listOf(Note(60, 1.0, 1.0, 1), Note(62, 5.0, 1.0, 2))
        val out = Repeats.applyToNotes(notes, marks, Repeats.expandRepeats(marks).order)
        assertEquals(listOf(60, 60, 62), out.map { it.midi })
        assertEquals(listOf(1.0, 5.0, 9.0), out.map { it.startBeat })
        assertEquals(listOf(1, 2, 3), out.map { it.measure })
    }

    @Test fun pickupLengthRespected() {
        val marks = listOf(MeasureMark(0.0, 1.0), MeasureMark(1.0, 4.0, repeatEnd = true))
        val notes = listOf(Note(60, 0.0, 1.0, 1), Note(62, 1.0, 4.0, 2))
        val out = Repeats.applyToNotes(notes, marks, Repeats.expandRepeats(marks).order)
        assertEquals(listOf(0.0, 1.0, 5.0, 6.0), out.map { it.startBeat })
    }
}
