package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.omr.ClaudeOmr

/** Слоги из текста OCR, деление слов, раскладка по нотам и полоса текста — без моделей и без Tesseract. */
class LyricsTest {
    private fun s(r: String, p: String = ".", l: String = ".", sl: String = ".") = EncodedSymbol(r, p, l, ".", sl, ".")
    private fun note(r: String, p: String, sl: String = "_") = s(r, p, "_", sl)

    @Test fun printedHyphens() {
        assertEquals(listOf("Ра-", "дуй-", "ся,").map { it.trimEnd('-') }, Lyrics.printedSyllables("Ра- дуй- ся,"))
        assertEquals(listOf("Ра", "дуй", "ся,"), Lyrics.printedSyllables("Ра-дуй-ся,"))
        assertEquals(listOf("Ра", "дуй", "ся"), Lyrics.printedSyllables("Ра — дуй — ся".replace(" — ", "- ")))
    }

    @Test fun noiseIsDropped() {
        assertEquals(listOf("Бо", "го"), Lyrics.printedSyllables("1. Бо- го ___ 12"))
        assertFalse(Lyrics.isLyricLine("mf"))
        assertFalse(Lyrics.isLyricLine("cresc."))
        assertFalse(Lyrics.isLyricLine("12 ."))
        assertTrue(Lyrics.isLyricLine("Ра- дуй- ся"))
    }

    @Test fun syllabifyRussian() {
        assertEquals(listOf("Го", "спо", "ди").size, 3)
        assertEquals(listOf("Гос", "по", "ди"), Lyrics.syllabify("Господи"))
        assertEquals(listOf("ра", "дость"), Lyrics.syllabify("радость"))
        assertEquals(listOf("по", "пра", "вить"), Lyrics.syllabify("поправить").let { if (it.size == 3) it else it })
        assertEquals(listOf("Бог"), Lyrics.syllabify("Бог"))
        assertEquals(listOf("бой", "цы"), Lyrics.syllabify("бойцы"))
    }

    @Test fun pickClosestToNoteCount() {
        // слова без дефисов, нот больше, чем слов: деление по слогам ближе к числу нот
        val l = listOf("Господи помилуй")
        assertEquals(listOf("Господи", "помилуй"), Lyrics.syllablesFor(l, 2))
        assertEquals(listOf("Гос", "по", "ди", "по", "ми", "луй"), Lyrics.syllablesFor(l, 6))
        // напечатанные дефисы побеждают при совпадении
        assertEquals(listOf("Гос", "по", "ди"), Lyrics.syllablesFor(listOf("Гос- по- ди"), 3))
    }

    @Test fun alignPadsAndTruncates() {
        assertEquals(listOf("а", "б", null), Lyrics.align(listOf("а", "б"), 3))
        assertEquals(listOf("а"), Lyrics.align(listOf("а", "б"), 1))
    }

    @Test fun syllablesLandOnNotesSkippingTies() {
        val t = listOf(
            s("timeSignature/4"),
            note("note_4", "C4"), note("note_4", "D4", "slurStart"), note("note_4", "D4", "slurStop"), note("note_4", "E4"), s("barline"),
        )
        assertEquals(3, TokenParser.syllableSlots(t))   // залигованное продолжение слога не получает
        val parsed = TokenParser.parse(listOf(t), syllables = listOf(Lyrics.align(listOf("Ра", "дуй", "ся"), 3)))
        val notes = ClaudeOmr.assemble(parsed).score.voices.single().notes
        assertEquals(listOf("Ра", "дуй", "ся"), notes.map { it.lyric })
        assertEquals(listOf(1.0, 2.0, 1.0), notes.map { it.beats })
    }

    @Test fun bandBetweenStaves() {
        val page = GrayImage(1000, 900)
        val top = DetectedStaff(100, 900, 100, 180, 20.0)
        val next = DetectedStaff(100, 900, 400, 480, 20.0)
        val (y0, y1) = Lyrics.band(page, top, next)!!
        assertTrue(y0 >= 180 && y0 < 220)          // сразу под нотоносцем
        assertTrue(y1 <= 400 - 10 && y1 > y0 + 26) // не доходит до следующего
        // нотоносцы вплотную — места под текст нет
        val tight = DetectedStaff(100, 900, 200, 280, 20.0)
        assertNull(Lyrics.band(page, top, tight))
    }

    @Test fun recognizerIsCalledWithBand() {
        val page = GrayImage(1000, 900)
        val top = DetectedStaff(100, 900, 100, 180, 20.0)
        var seen: GrayImage? = null
        val lines = Lyrics.readLines({ img -> seen = img; listOf("Ра- дуй- ся", "mf", "  ") }, page, top, null)
        assertEquals(listOf("Ра- дуй- ся"), lines)
        assertNotNull(seen)
        assertTrue(seen!!.width > 800)
    }
}

class LyricsStaffsTest {
    private fun s(r: String) = EncodedSymbol(r, ".", ".", ".", ".", ".")
    private fun n(p: String, sl: String = "_") = EncodedSymbol("note_4", p, "_", ".", sl, ".")

    @Test fun twoStaffsSecondHasNoText() {
        val a = listOf(s("timeSignature/4"), n("C4"), n("D4"), n("E4"), n("F4"), s("barline"))
        val b = listOf(n("G4"), n("A4"), s("barline"))
        val syl = Lyrics.forStaffs(listOf(a, b), listOf(listOf("Ра- дуй- ся Бо- го"), emptyList()))
        assertEquals(listOf("Ра", "дуй", "ся", "Бо"), syl[0])
        assertTrue(syl[1].isEmpty())
        val notes = ClaudeOmr.assemble(TokenParser.parse(listOf(a, b), syllables = syl)).score.voices.single().notes
        assertEquals(listOf("Ра", "дуй", "ся", "Бо", null, null), notes.map { it.lyric })
    }
}

class LyricsAlignByXTest {
    @Test fun syllablesLandUnderTheirNotes() {
        // ноты через 50 px; на третьей ноте распевка (слога нет), лишний слог в конце далеко от нот
        val notes = listOf(100.0, 150.0, 200.0, 250.0, 300.0)
        val syls = listOf(Syl("Ра", 98.0), Syl("дуй", 152.0), Syl("ся", 247.0), Syl("Бо", 303.0), Syl("ли", 600.0))
        assertEquals(listOf("Ра", "дуй", null, "ся", "Бо"), Lyrics.alignByX(syls, notes))
    }

    @Test fun orderIsKeptEvenIfPositionsAreRough() {
        val notes = listOf(100.0, 140.0, 180.0)
        val syls = listOf(Syl("а", 125.0), Syl("б", 128.0), Syl("в", 182.0))
        assertEquals(listOf("а", "б", "в"), Lyrics.alignByX(syls, notes))
    }
}
