package ru.partitrainer.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.omr.SourceLayout
import ru.partitrainer.core.structure.Jump
import ru.partitrainer.core.structure.MeasureMark

/**
 * Совместимость сохранённых данных: файлы в `resources/compat` записаны в формате, в котором приложение хранит проекты
 * (база: ноты и разметка повторов в JSON) и разметку исходника (`layouts/<id>.json`). Если изменение модели перестанет их
 * читать, уже сохранённые проекты у пользователей не откроются — тест упадёт раньше.
 * Json — как в Repository/LayoutStore/OmrWorker (неизвестные поля пропускаются).
 */
class FormatCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun res(name: String) = javaClass.getResource("/compat/$name")!!.readText()

    @Test fun savedScoreStillReads() {
        val s = json.decodeFromString(Score.serializer(), res("score_v1.json"))
        assertEquals("Тест совместимости", s.title)
        assertEquals(-1, s.keyFifths); assertEquals(3, s.timeNum); assertEquals(4, s.timeDen); assertEquals(90, s.tempo)
        assertEquals(listOf("omr_part_0", "omr_part_1"), s.voices.map { it.id })
        assertEquals(VoiceKind.TENOR, s.voices[1].kind)
        val n = s.voices[0].notes
        assertEquals(listOf(67, 69, 70), n.map { it.midi })
        assertEquals(1.5, n[0].beats, 0.0); assertEquals("Ра-", n[0].lyric); assertNull(n[2].lyric)
        assertEquals(s, json.decodeFromString(Score.serializer(), json.encodeToString(Score.serializer(), s)))
    }

    @Test fun savedRepeatMarksStillRead() {
        val m = json.decodeFromString(ListSerializer(MeasureMark.serializer()), res("marks_v1.json"))
        assertEquals(3, m.size)
        assertTrue(m[0].repeatStart); assertTrue(m[1].repeatEnd); assertEquals(listOf(1), m[1].volta)
        assertEquals(2, m[1].repeatTimes)                          // поле не записано — значение по умолчанию
        assertEquals(Jump.DC_AL_FINE, m[2].jump)
    }

    @Test fun savedSourceLayoutStillReads() {
        val l = json.decodeFromString(SourceLayout.serializer(), res("layout_v1.json"))
        assertEquals(listOf(0, 2), l.pages.map { it.index }); assertEquals(90, l.pages[1].rotation)
        assertNull(l.rects[1]); assertEquals(0.9f, l.rects[2]!!.x1, 0f)
        assertEquals(3, l.notes.getValue("omr_part_0").size); assertNull(l.notes.getValue("omr_part_0")[1])
        // разметка, сохранённая до появления положений нот
        val old = json.decodeFromString(SourceLayout.serializer(), res("layout_v0_without_notes.json"))
        assertTrue(old.notes.isEmpty()); assertEquals(0, old.pages[0].rotation)
    }
}
