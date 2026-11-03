package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.omr.ClaudeOmr

/** Разбор токенов без моделей: переменный метр, лиги через черту, аккорды, паузы-заполнители. */
class TokenParserTest {
    private fun s(r: String, p: String = ".", l: String = ".", sl: String = ".") = EncodedSymbol(r, p, l, ".", sl, ".")
    private fun note(r: String, p: String, l: String = "_", sl: String = "_") = s(r, p, l, sl)

    @Test fun changingMeter() {
        val t = listOf(
            s("clef_G2"), s("keySignature_-3"), s("timeSignature/4"),
            note("note_4", "C4"), note("note_4", "D4"), note("note_4", "E4"), note("note_4", "F4"), s("barline"),
            s("timeSignature/4"),
            note("note_4", "C4"), note("note_4", "D4"), note("note_4", "E4"), note("note_4", "F4"), note("note_4", "G4"), note("note_4", "A4"), s("barline"),
            s("timeSignature/4"),
            note("note_4", "C4"), note("note_4", "D4"), note("note_4", "E4"), note("note_4", "F4"), note("note_4", "G4"), s("barline"),
        )
        val p = TokenParser.parse(listOf(t))
        assertEquals(-3, p.keyFifths)
        assertEquals(listOf(4, 6, 5), p.measures.map { it.timeNum })
        assertTrue("в метре нет ошибок сумм", ClaudeOmr.badMeasures(p).isEmpty())
        val notes = ClaudeOmr.assemble(p).score.voices.single().notes
        // ноты не накладываются друг на друга и идут подряд
        notes.zipWithNext().forEach { (a, b) -> assertEquals(a.endBeat, b.startBeat, 1e-9) }
        assertEquals(15.0, notes.last().endBeat, 1e-9)
        assertEquals(listOf(1, 2, 3), notes.map { it.measure }.distinct())
    }

    @Test fun tieAcrossBarlineAndRests() {
        val t = listOf(
            s("timeSignature/4"),
            note("note_2", "G4", sl = "slurStart"), note("note_2", "A4"), s("barline"),
            note("note_2", "A4", sl = "slurStart"), note("note_2", "A4", sl = "slurStop"), s("barline"),
            s("rest_measure"), s("barline"),
        )
        // первая лига: A4 (такт 1, без slurStart) — не лига; вторая: A4→A4 внутри такта 2 — лига
        val p = TokenParser.parse(listOf(t))
        val notes = ClaudeOmr.assemble(p).score.voices.single().notes
        assertEquals(listOf(67, 69, 69), notes.map { it.midi })
        assertEquals(4.0, notes.last().beats, 1e-9) // A4 2 + A4 2 слиты
        assertEquals(3, p.measures.size)
        assertEquals(4.0, p.measures[2].total, 1e-9) // пауза на целый такт
    }

    @Test fun chordKeepsTopNoteAndTranspose() {
        val t = listOf(s("timeSignature/4"), note("note_1", "C4"), s("chord"), note("note_1", "E4"), s("barline"))
        assertEquals(64, ClaudeOmr.assemble(TokenParser.parse(listOf(t))).score.voices.single().notes.single().midi)
        assertEquals(52, ClaudeOmr.assemble(TokenParser.parse(listOf(t), transpose = -12)).score.voices.single().notes.single().midi)
    }

    @Test fun implicitMeterChangeWithoutSignature() {
        // как на странице Agnus Dei: 3/2, затем 4 доли ×2 без знака размера
        fun q(n: Int) = (0 until n).map { note("note_4", "C4") }
        val t = listOf(s("timeSignature/2")) + q(6) + s("barline") + q(4) + s("barline") + q(4) + s("barline") + q(4) + s("barline")
        val p = TokenParser.parse(listOf(t))
        assertEquals(listOf(6.0, 4.0, 4.0, 4.0), p.measures.map { it.total })
        assertEquals(listOf(6, 4, 4, 4), p.measures.map { it.timeNum!! * 4 / it.timeDen!! })
        assertTrue(ClaudeOmr.badMeasures(p).isEmpty())
    }
}
