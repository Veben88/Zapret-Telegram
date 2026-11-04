package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test

/** Случаи из тестов homr (triplet_restoration), записаны заново. */
class TripletsTest {
    /** Строки «rhythm pitch lift art slur pos», одновременные символы через &. */
    private fun parse(text: String): List<EncodedSymbol> = text.trim().lines().flatMapIndexed { _, line ->
        line.split('&').flatMapIndexed { i, part ->
            val f = part.trim().split(Regex("\\s+"))
            val sym = EncodedSymbol(f[0], f.getOrElse(1) { "." }, f.getOrElse(2) { "." }, f.getOrElse(3) { "." }, f.getOrElse(4) { "." }, f.getOrElse(5) { "." })
            if (i == 0) listOf(sym) else listOf(EncodedSymbol("chord", ".", ".", ".", ".", "."), sym)
        }
    }

    private val header = "clef_G2 _ _ _ _ upper&clef_F4 _ _ _ _ lower\nkeySignature_0 . . . . .\ntimeSignature/4 . . . . ."
    private val threesAgainstQuarters = """
        note_8 C5 _ _ _ upper&note_4 C3 _ _ _ lower
        note_8 D5 _ _ _ upper
        note_8 E5 _ _ _ upper
        note_8 F5 _ _ _ upper&note_4 G2 _ _ _ lower
        note_8 E5 _ _ _ upper
        note_8 D5 _ _ _ upper
        barline . . . . .
    """.trimIndent()
    private val pairsAgainstQuarters = """
        note_8 C5 _ _ _ upper&note_4 C3 _ _ _ lower
        note_8 D5 _ _ _ upper
        note_8 E5 _ _ _ upper&note_4 G2 _ _ _ lower
        note_8 F5 _ _ _ upper
        barline . . . . .
    """.trimIndent()
    private val sixAlone = """
        note_8 C5 _ _ _ upper
        note_8 D5 _ _ _ upper
        note_8 E5 _ _ _ upper
        note_8 F5 _ _ _ upper
        note_8 E5 _ _ _ upper
        note_8 D5 _ _ _ upper
        barline . . . . .
    """.trimIndent()

    private fun rhythms(s: List<EncodedSymbol>) = s.filter { it.rhythm.startsWith("note") }.map { it.rhythm }

    @Test fun threesAgainstQuartersBecomeTriplets() {
        val staff = parse(header + "\n" + List(3) { threesAgainstQuarters }.joinToString("\n"))
        val r = Triplets.restore(listOf(staff)).single()
        assertEquals(18, rhythms(r).count { it == "note_12" })        // все восьмые правой руки
        assertEquals(6, rhythms(r).count { it == "note_4" })          // четверти левой руки не тронуты
        // и такты теперь сходятся
        assertTrue(ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(listOf(Divisi.primary(r)))).isEmpty())
    }

    @Test fun tooFewPlacesOnThePageKeepPlainNotes() {
        val staff = parse(header + "\n" + threesAgainstQuarters)
        assertEquals(staff, Triplets.restore(listOf(staff)).single())
    }

    @Test fun measuresThatAddUpAreNotChanged() {
        val staff = parse(header + "\n" + List(3) { pairsAgainstQuarters }.joinToString("\n"))
        assertEquals(staff, Triplets.restore(listOf(staff)).single())
        val alone = parse(header + "\n" + List(3) { sixAlone }.joinToString("\n"))
        assertEquals(alone, Triplets.restore(listOf(alone)).single())
    }

    @Test fun staffBoundaryIsKept() {
        val a = parse(header + "\n" + List(2) { threesAgainstQuarters }.joinToString("\n"))
        val b = parse(threesAgainstQuarters)
        val r = Triplets.restore(listOf(a, b))
        assertEquals(a.size, r[0].size); assertEquals(b.size, r[1].size)
        assertTrue(r[1].any { it.rhythm == "note_12" })
    }

    @Test fun durations() {
        assertEquals(Triplets.Frac(1, 4), Triplets.duration("note_4"))
        assertEquals(Triplets.Frac(1, 12), Triplets.duration("note_12"))
        assertEquals(Triplets.Frac(3, 8), Triplets.duration("note_4."))
        assertEquals(Triplets.Frac.ZERO, Triplets.duration("note_8G"))
    }
}
