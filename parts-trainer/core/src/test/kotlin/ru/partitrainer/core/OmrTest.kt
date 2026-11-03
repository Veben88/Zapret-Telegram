package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.edit.EditHistory
import ru.partitrainer.core.edit.NoteEditor
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.omr.ClaudeOmr
import ru.partitrainer.core.omr.OmrFormatException
import ru.partitrainer.core.structure.Jump

class OmrTest {
    private val json = """
        {"title":"Песня","keyFifths":1,"timeSig":[4,4],"tempo":100,
         "measures":[
          {"n":1,"chord":"G","pickup":true,"events":[{"p":"D4","d":1}],"repeat":{"start":true,"end":false,"ending":null},"marks":["segno"]},
          {"n":2,"chord":"G","pickup":false,"events":[{"p":"B4","d":1},{"p":"r","d":0.5},{"p":"A4","d":0.5,"tie":true,"syl":"ля"},{"p":"A4","d":2}],"repeat":{"start":false,"end":false,"ending":1},"marks":[]},
          {"n":3,"events":[{"p":"G4","d":2},{"p":"C5","d":1}],"repeat":{"start":false,"end":true,"ending":null},"marks":["D.S. al Coda"]}
         ],
         "uncertain":[{"measure":3,"reason":"размыто"}]}
    """.trimIndent()

    @Test fun parse() {
        val p = ClaudeOmr.parse(json)
        assertEquals("Песня", p.title); assertEquals(1, p.keyFifths); assertEquals(100, p.tempo)
        assertEquals(3, p.measures.size)
        assertTrue(p.measures[0].pickup)
        assertEquals(71, p.measures[1].events[0].midi)
        assertNull(p.measures[1].events[1].midi)
        assertTrue(p.measures[2].uncertain)
        assertEquals("размыто", p.measures[2].uncertainReason)
        assertEquals(1, p.measures[1].ending)
    }

    @Test fun parseWithFencesAndChatter() {
        val p = ClaudeOmr.parse("Вот результат:\n```json\n$json\n```\nготово")
        assertEquals(3, p.measures.size)
    }

    @Test fun invalid() {
        assertThrows(OmrFormatException::class.java) { ClaudeOmr.parse("не json") }
        assertThrows(OmrFormatException::class.java) { ClaudeOmr.parse("""{"measures":[{"events":[{"p":"Q9","d":1}]}]}""") }
        assertThrows(OmrFormatException::class.java) { ClaudeOmr.parse("""{"measures":[]}""") }
        assertThrows(OmrFormatException::class.java) { ClaudeOmr.parse("""{"title":"x"}""") }
        assertThrows(OmrFormatException::class.java) { ClaudeOmr.parse("""{"measures":[{"events":[{"p":"C4","d":0}]}]}""") }
    }

    @Test fun badMeasuresSkipPickupAndLast() {
        val p = ClaudeOmr.parse(json)
        // Такт 2 = 4 доли — верен; такт 1 — затакт; такт 3 (последний, 3 доли) не проверяется
        assertTrue(ClaudeOmr.badMeasures(p).isEmpty())
        val broken = ClaudeOmr.parse("""{"measures":[{"n":1,"events":[{"p":"C4","d":3}]},{"n":2,"events":[{"p":"C4","d":4}]},{"n":3,"events":[{"p":"C4","d":1}]}]}""")
        assertEquals(listOf(0), ClaudeOmr.badMeasures(broken))
        val marked = ClaudeOmr.withUncertain(broken, listOf(0))
        assertTrue(marked.measures[0].uncertain)
    }

    @Test fun assemble() {
        val a = ClaudeOmr.assemble(ClaudeOmr.parse(json))
        val n = a.score.voices.single().notes
        // D4 затакт (1 доля), B4, [пауза], A4+A4 слиты в 2.5 доли, G4, C5
        assertEquals(listOf(62, 71, 69, 67, 72), n.map { it.midi })
        assertEquals(0.0, n[0].startBeat, 1e-9)
        assertEquals(1.0, n[1].startBeat, 1e-9)       // затакт длиной 1 доля
        assertEquals(2.5, n[2].startBeat, 1e-9)
        assertEquals(2.5, n[2].beats, 1e-9)
        assertEquals("ля", n[2].lyric)
        assertEquals(5.0, n[3].startBeat, 1e-9)
        assertEquals(3, a.marks.size)
        assertEquals(1.0, a.marks[0].lengthBeats, 1e-9)
        assertTrue(a.marks[0].segno); assertTrue(a.marks[0].repeatStart)
        assertEquals(Jump.DS_AL_CODA, a.marks[2].jump)
        assertEquals(listOf(2), a.uncertainMeasures)
    }

    @Test fun costEstimate() {
        val c = ClaudeOmr.estimate(4, 1600, 2200)
        assertTrue(c.usd > 0 && c.usd < 5)
        assertTrue(c.inputTokens > 4 * 1000)
    }
}

class EditorTest {
    private val ed = NoteEditor.forMarks(emptyList(), 4.0)
    private val base = listOf(Note(60, 0.0, 1.0, 1), Note(62, 1.0, 1.0, 1), Note(64, 3.0, 1.0, 1))

    @Test fun pitch() {
        assertEquals(61, ed.shiftPitch(base, 0, 1)[0].midi)
        assertEquals(58, ed.shiftPitch(base, 0, -2)[0].midi)
        assertEquals(70, ed.setPitch(base, 1, 70)[1].midi)
    }

    @Test fun duration() {
        assertEquals(2.0, ed.setDuration(base, 2, 2.0)[2].beats, 0.0)
        assertEquals(2.0, ed.setDuration(base, 1, 5.0)[1].beats, 0.0) // ограничено началом следующей ноты
    }

    @Test fun restAndInsert() {
        val r = ed.toRest(base, 1)
        assertEquals(2, r.size)
        val ins = ed.insert(r, 66, 1.0, 5.0)  // вставка в промежуток обрезается по следующей
        assertEquals(3, ins.size)
        assertEquals(2.0, ins[1].beats, 1e-9)
    }

    @Test fun splitMerge() {
        val s = ed.split(base, 2, 0.25)
        assertEquals(4, s.size)
        assertEquals(3.25, s[3].startBeat, 1e-9)
        assertEquals(0.75, s[3].beats, 1e-9)
        val m = ed.mergeWithNext(s, 2)
        assertEquals(base[2].beats, m[2].beats, 1e-9)
        assertEquals(base, ed.mergeWithNext(base, 5)) // выход за границы — без изменений
    }

    @Test fun measureRenumberOnInsert() {
        val r = ed.insert(base, 65, 4.0, 1.0)
        assertEquals(2, r.last().measure)
    }

    @Test fun history() {
        val h = EditHistory(base)
        val a = ed.shiftPitch(h.current, 0, 2); h.push(a)
        val b = ed.toRest(a, 1); h.push(b)
        assertEquals(a, h.undo())
        assertEquals(base, h.undo())
        assertFalse(h.canUndo)
        assertEquals(a, h.redo())
        h.push(ed.shiftPitch(a, 0, 1))
        assertFalse(h.canRedo)
    }
}

class LayoutTest {
    @Test fun layoutWithRests() {
        val notes = listOf(Note(60, 0.0, 1.0, 1), Note(62, 2.0, 1.0, 1), Note(64, 4.0, 4.0, 2))
        val v = ru.partitrainer.core.edit.MeasureLayout.build(notes, emptyList(), 4.0)
        assertEquals(2, v.size)
        assertEquals(listOf("N", "R", "N", "R"), v[0].cells.map { if (it is ru.partitrainer.core.edit.NoteCell) "N" else "R" })
        assertTrue(v[0].balanced)
        assertTrue(v[1].balanced)
        assertEquals(2, v[1].number)
    }

    @Test fun layoutMarksPickup() {
        val marks = listOf(ru.partitrainer.core.structure.MeasureMark(0.0, 1.0), ru.partitrainer.core.structure.MeasureMark(1.0, 4.0))
        val notes = listOf(Note(60, 0.0, 1.0, 1), Note(62, 1.0, 3.0, 2))
        val v = ru.partitrainer.core.edit.MeasureLayout.build(notes, marks, 4.0)
        assertTrue(v[0].balanced)
        assertTrue(v[1].cells.last() is ru.partitrainer.core.edit.NoteCell) // последний такт без «хвоста» паузы
    }

    @Test fun beatsFormat() {
        val f = ru.partitrainer.core.music.BeatsFormat::format
        assertEquals("½", f(0.5)); assertEquals("1½", f(1.5)); assertEquals("2", f(2.0)); assertEquals("¼", f(0.25))
        assertEquals("15", f(15.0)); assertEquals("0.2", f(0.2))
    }
}

class StaffFinderTest {
    private fun page(w: Int, h: Int, stavesTop: List<Int>, spacing: Int): IntArray {
        val img = IntArray(w * h) { 255 }
        for (t in stavesTop) for (l in 0 until 5) { val y = t + l * spacing; for (x in 20 until w - 20) img[y * w + x] = 0 }
        // «нотные палочки» и текст — не должны сбивать
        for (x in 100 until 104) for (y in 0 until h step 3) img[y * w + x] = 0
        return img
    }

    @Test fun findsSixStaves() {
        val tops = listOf(60, 140, 200, 360, 440, 500)
        val st = ru.partitrainer.core.omr.StaffFinder.find(page(600, 800, tops, 8), 600, 800)
        assertEquals(6, st.size)
        assertEquals(tops, st.map { it.top })
        assertEquals(3, ru.partitrainer.core.omr.StaffFinder.perSystem(st.size))
        assertEquals(listOf(60, 360), ru.partitrainer.core.omr.StaffFinder.select(st, 0).map { it.top })
        assertEquals(listOf(200, 500), ru.partitrainer.core.omr.StaffFinder.select(st, 2).map { it.top })
        assertEquals(6, ru.partitrainer.core.omr.StaffFinder.select(st, null).size)
    }

    @Test fun emptyPage() {
        assertTrue(ru.partitrainer.core.omr.StaffFinder.find(IntArray(100 * 100) { 255 }, 100, 100).isEmpty())
    }
}
