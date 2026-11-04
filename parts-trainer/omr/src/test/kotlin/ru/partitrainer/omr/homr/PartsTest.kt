package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test

class PartsTest {
    private fun n(r: String, p: String, pos: String = "upper") = EncodedSymbol(r, p, "_", "_", "_", pos)
    private fun s(r: String) = EncodedSymbol(r, ".", ".", ".", ".", ".")
    private fun read(i: Int, clef: Char, median: Int, tokens: List<EncodedSymbol>, oct8: Boolean = false) =
        StaffRead(DetectedStaff(0, 1000, i * 200, i * 200 + 40, 10.0), 0, i, tokens, clef, oct8, median)

    private val melody = listOf(s("clef_G2"), n("note_4", "C5"), n("note_4", "D5"), n("note_4", "E5"), n("note_4", "F5"), s("barline"))
    private val low = listOf(s("clef_G2"), n("note_4", "C4"), n("note_4", "D4"), n("note_4", "E4"), n("note_4", "F4"), s("barline"))
    private val bass = listOf(s("clef_F4"), n("note_4", "C3"), n("note_4", "D3"), n("note_4", "E3"), n("note_4", "F3"), s("barline"))

    @Test fun threeAltosAndTwoSopranos() {
        // S1, S2, A1, A2, A3 (пять скрипичных без «8»), бас
        val sys = listOf(read(0, 'G', 74, melody), read(1, 'G', 70, melody), read(2, 'G', 62, low), read(3, 'G', 61, low), read(4, 'G', 60, low), read(5, 'F', 48, bass))
        val names = Parts.describe(sys).map { it.name }
        assertEquals(listOf("Сопрано 1", "Сопрано 2", "Альт 1", "Альт 2", "Альт 3", "Бас"), names)
    }

    @Test fun tenorWithoutRecognizedOctaveMark() {
        // S, A, T (скрипичный, «8» не распознана, на письме выше альта), B
        val tenor = listOf(s("clef_G2"), n("note_4", "E5"), n("note_4", "F5"), n("note_4", "G5"), n("note_4", "A5"), s("barline"))
        val sys = listOf(read(0, 'G', 70, melody), read(1, 'G', 64, low), read(2, 'G', 75, tenor), read(3, 'F', 48, bass))
        val parts = Parts.describe(sys)
        assertEquals(listOf("Сопрано", "Альт", "Тенор", "Бас"), parts.map { it.name })
        assertTrue(parts[2].lowerOctave)
    }

    @Test fun divisiOnOneStaffGivesTwoParts() {
        val two = listOf(s("clef_G2"), n("note_4", "E5"), s("chord"), n("note_4", "C5", "upper2"), n("note_4", "F5"), s("chord"), n("note_4", "D5", "upper2"),
            n("note_4", "G5"), s("chord"), n("note_4", "E5", "upper2"), n("note_4", "F5"), s("chord"), n("note_4", "D5", "upper2"), s("barline"))
        val sys = listOf(read(0, 'G', 74, two), read(1, 'G', 62, low), read(2, 'G', 66, melody, oct8 = true), read(3, 'F', 48, bass))
        val parts = Parts.describe(sys)
        assertEquals(listOf("Сопрано 1", "Сопрано 2", "Альт", "Тенор", "Бас"), parts.map { it.name })
        assertEquals(listOf(1, 2), parts.take(2).map { it.stemVoice })
    }

    @Test fun earlyMusicWithOctaveTrebleForLowerVoices() {
        // Superius, Altus, Tenor, Bassus: у трёх нижних «соль с 8», басового ключа нет
        val sys = listOf(read(0, 'G', 70, melody), read(1, 'G', 77, melody, oct8 = true), read(2, 'G', 76, melody, oct8 = true), read(3, 'G', 70, low, oct8 = true))
        val parts = Parts.describe(sys)
        assertEquals(listOf("Сопрано", "Альт", "Тенор", "Бас"), parts.map { it.name })
        assertEquals(listOf(false, true, true, true), parts.map { it.lowerOctave })
        val (_, shift) = Parts.tokensFor(parts[1], listOf(sys))
        assertEquals(-12, shift)                                     // альт с «8» звучит октавой ниже
        assertTrue(Parts.withKind(parts, 3, ChoirVoice.TENOR)[3].lowerOctave)
    }

    @Test fun encodeDecodeRoundTrip() {
        val p = PartPick(2, 5, 'G', false, 2, 1, "Альт 2", ChoirVoice.ALTO, false, true, 1)
        assertEquals(p, PartPick.decode(p.encode()))
    }

    @Test fun partFoundInSystemWithFewerStaves() {
        val five = listOf(read(0, 'G', 74, melody), read(1, 'G', 70, melody), read(2, 'G', 62, low), read(3, 'G', 66, melody, oct8 = true), read(4, 'F', 48, bass))
        val four = listOf(read(0, 'G', 72, melody), read(1, 'G', 62, low), read(2, 'G', 66, melody, oct8 = true), read(3, 'F', 48, bass))
        val parts = Parts.describe(five)
        val s2 = parts.first { it.name == "Сопрано 2" }; val a = parts.first { it.name == "Альт" }
        assertEquals(0, Parts.staffFor(s2, four)!!.first.indexInSystem)   // сопрано одной строкой — её поют обе партии
        assertEquals(1, Parts.staffFor(a, four)!!.first.indexInSystem)
    }

    @Test fun userFixesVoice() {
        val sys = listOf(read(0, 'G', 70, melody), read(1, 'G', 62, low), read(2, 'G', 66, melody, oct8 = true), read(3, 'F', 48, bass))
        val parts = Parts.describe(sys)
        val fixed = Parts.withKind(parts, 1, ChoirVoice.TENOR)
        assertEquals(listOf("Сопрано", "Тенор 1", "Тенор 2", "Бас"), fixed.map { it.name })
        assertTrue(fixed[1].lowerOctave)
    }
}
