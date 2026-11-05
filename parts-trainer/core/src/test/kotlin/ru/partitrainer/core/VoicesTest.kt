package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.io.RawNote
import ru.partitrainer.core.io.RawPart
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.voices.VoiceClassifier

class VoicesTest {
    private fun line(vararg midi: Int, staff: Int = 1, voice: String = "1") =
        midi.mapIndexed { i, m -> RawNote(Note(m, i.toDouble(), 1.0, 1), staff, voice) }

    @Test fun names() {
        val k = VoiceClassifier::kindFromName
        assertEquals(VoiceKind.SOPRANO, k("Сопрано 1"))
        assertEquals(VoiceKind.SOPRANO, k("Soprano"))
        assertEquals(VoiceKind.SOPRANO, k("S"))
        assertEquals(VoiceKind.ALTO, k("A."))
        assertEquals(VoiceKind.ALTO, k("Alto"))
        assertEquals(VoiceKind.TENOR, k("T2"))
        assertEquals(VoiceKind.TENOR, k("Тенор"))
        assertEquals(VoiceKind.BASS, k("Bass"))
        assertEquals(VoiceKind.BASS, k("Бас"))
        assertEquals(VoiceKind.MELODY, k("Vocals"))
        assertEquals(VoiceKind.MELODY, k("Voice"))
        assertNull(k("Piano"))
        assertNull(k("Acoustic Guitar")) // «A» внутри слова не должно срабатывать
        assertNull(k(""))
    }

    @Test fun byPartName() {
        val v = VoiceClassifier.classify(listOf(
            RawPart("Soprano", "", line(72, 74)), RawPart("Alto", "", line(65, 67)),
            RawPart("Tenor", "", line(55, 57)), RawPart("Bass", "", line(43, 45)),
        ))
        assertEquals(listOf(VoiceKind.SOPRANO, VoiceKind.ALTO, VoiceKind.TENOR, VoiceKind.BASS), v.map { it.kind })
    }

    @Test fun byInstrumentName() {
        val v = VoiceClassifier.classify(listOf(RawPart("Part 1", "Vocals", line(60))))
        assertEquals(VoiceKind.MELODY, v.single().kind)
    }

    @Test fun byVoiceAndStaffInOnePart() {
        val notes = line(72, 74, voice = "1") + line(60, 62, voice = "2")
        val v = VoiceClassifier.classify(listOf(RawPart("Piano", "", notes)))
        assertEquals(2, v.size)
        assertTrue(v.all { it.kind == VoiceKind.OTHER })
        assertEquals(listOf("Голос 1", "Голос 2"), v.map { it.label }) // сверху вниз по средней высоте
        assertEquals(72, v[0].notes[0].midi)
    }

    @Test fun chordsSplitByPitch() {
        // Аккорды из 3 нот в одном нотоносце
        val notes = (0 until 3).flatMap { i -> listOf(60, 67, 72).map { RawNote(Note(it, i.toDouble(), 1.0, 1)) } }
        val v = VoiceClassifier.classify(listOf(RawPart("", "", notes)))
        assertEquals(3, v.size)
        assertEquals(listOf(72, 67, 60), v.map { it.notes[0].midi })
        assertEquals(3, v[0].notes.size)
    }

    @Test fun combinedNames() {
        val notes = (0 until 2).flatMap { i -> listOf(72, 64).map { RawNote(Note(it, i.toDouble(), 1.0, 1)) } }
        val v = VoiceClassifier.classify(listOf(RawPart("Soprano/Alto", "", notes)))
        assertEquals(listOf(VoiceKind.SOPRANO, VoiceKind.ALTO), v.map { it.kind })
        assertEquals(72, v[0].notes[0].midi)
    }

    @Test fun unnamedSortedByAveragePitch() {
        val v = VoiceClassifier.classify(listOf(
            RawPart("", "", line(50, 52)), RawPart("", "", line(70, 72)), RawPart("", "", line(60, 62)),
        ))
        val byLabel = v.associateBy { it.label }
        assertEquals(70, byLabel.getValue("Голос 1").notes[0].midi)
        assertEquals(60, byLabel.getValue("Голос 2").notes[0].midi)
        assertEquals(50, byLabel.getValue("Голос 3").notes[0].midi)
        val assigned = VoiceClassifier.withKind(v[0], VoiceKind.TENOR)
        assertEquals(VoiceKind.TENOR, assigned.kind)
        assertEquals("Тенор", assigned.label)
    }
}
