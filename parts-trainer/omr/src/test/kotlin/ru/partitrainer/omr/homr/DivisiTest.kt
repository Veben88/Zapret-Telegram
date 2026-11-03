package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.omr.ClaudeOmr

class DivisiTest {
    private fun x(r: String, p: String = ".", l: String = "_") = EncodedSymbol(r, p, l, ".", "_", ".")
    private fun n(r: String, p: String, l: String = "_") = x(r, p, l)
    private fun ctrl(r: String) = x(r)

    @Test fun differentDurationsInOneChord() {
        // как у модели: «n4:C5 chord n1:F4 n4:C5 n4:C5 n4:B4b |» — сопрано четвертями, альт держит целую
        val t = listOf(ctrl("timeSignature/4"), n("note_4", "C5"), ctrl("chord"), n("note_1", "F4"), n("note_4", "C5"), n("note_4", "C5"), n("note_4", "B4", "b"), ctrl("barline"))
        val sp = Divisi.split(t)
        val up = ClaudeOmr.assemble(TokenParser.parse(listOf(sp.upper))).score.voices.single().notes
        val lo = ClaudeOmr.assemble(TokenParser.parse(listOf(sp.lower))).score.voices.single().notes
        assertEquals(listOf(72, 72, 72, 70), up.map { it.midi })
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), up.map { it.beats })
        assertEquals(listOf(65), lo.map { it.midi })
        assertEquals(4.0, lo.single().beats, 1e-9)
    }

    @Test fun singleNoteGoesToFreeVoiceOrBoth() {
        val t = listOf(
            ctrl("timeSignature/4"),
            n("note_2", "E5"), n("note_2", "D5"), ctrl("barline"),                // оба свободны — унисон
            n("note_2", "C5"), ctrl("chord"), n("note_2", "G4"), ctrl("barline"), // дивизи
        )
        val sp = Divisi.split(t)
        val up = ClaudeOmr.assemble(TokenParser.parse(listOf(sp.upper))).score.voices.single().notes.map { it.midi }
        val lo = ClaudeOmr.assemble(TokenParser.parse(listOf(sp.lower))).score.voices.single().notes.map { it.midi }
        assertEquals(listOf(76, 74, 72), up)
        assertEquals(listOf(76, 74, 67), lo)
    }

    @Test fun restBelowNoteBelongsToLowerVoice() {
        val t = listOf(ctrl("timeSignature/4"), n("note_1", "C5"), ctrl("barline"), n("note_4", "B4"), ctrl("chord"), x("rest_4"), n("note_2.", "B4"), ctrl("barline"))
        val sp = Divisi.split(t)
        val lo = TokenParser.parse(listOf(sp.lower)).measures
        assertTrue(lo[1].events.first().midi == null)  // нижний голос: пауза
    }

    private fun staff(clef: Char, median: Int, tokens: List<EncodedSymbol>, idx: Int) =
        StaffRead(DetectedStaff(0, 100, 0, 10, 10.0), 0, idx, tokens, clef, false, median)

    @Test fun fourStaffSystemUsesPositionNotContent() {
        // хор с плотными аккордами на обеих строках: по содержанию он выглядел бы как рояль
        val dense = (0 until 6).flatMap { listOf(n("note_4", "C5"), ctrl("chord"), n("note_4", "E4"), ctrl("chord"), n("note_4", "G4"), ctrl("chord"), n("note_4", "C4")) }
        val sys = listOf(staff('G', 70, dense, 0), staff('F', 50, dense, 1), staff('G', 61, dense, 2), staff('F', 39, dense, 3))
        val a = ChoirAssigner.assignTwoStaff(sys)
        assertEquals(0, a.sa?.indexInSystem)
        assertEquals(1, a.tb?.indexInSystem)
    }

    @Test fun pianoStavesAreSkipped() {
        val chordy = (0 until 6).flatMap { listOf(n("note_4", "C5"), ctrl("chord"), n("note_4", "E4"), ctrl("chord"), n("note_4", "G4"), ctrl("chord"), n("note_4", "C4")) }
        val sys = listOf(
            staff('G', 72, listOf(n("note_4", "C5"), ctrl("chord"), n("note_4", "E4")), 0),   // SA
            staff('F', 60, listOf(n("note_4", "C3")), 1),                                      // TB
            staff('G', 61, chordy, 2),                                                          // фортепиано, правая
            staff('F', 39, listOf(n("note_4", "C2")), 3),                                      // фортепиано, левая
        )
        val a = ChoirAssigner.assignTwoStaff(sys)
        assertEquals(0, a.sa?.indexInSystem)
        assertEquals(1, a.tb?.indexInSystem)
        // система из двух строк: рояль определяется по содержанию
        val pianoOnly = ChoirAssigner.assignTwoStaff(listOf(staff('G', 61, chordy, 0), staff('F', 39, listOf(n("note_4", "C2")), 1)))
        assertNull(pianoOnly.sa); assertNull(pianoOnly.tb)
    }
}
