package ru.partitrainer

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.data.OmrCacheEntity
import ru.partitrainer.data.PartiDatabase
import ru.partitrainer.data.ProjectEntity
import ru.partitrainer.omr.OmrResultDto

/**
 * Совместимость хранилища: версия базы и набор столбцов не меняются без миграции, кэш распознавания прежнего формата
 * читается. Если тест упал — нужна миграция Room (и новая версия базы), иначе сохранённые проекты не откроются.
 */
class CompatibilityTest {
    @Test fun databaseVersionAndColumnsAreUnchanged() {
        // аннотация Room не видна через отражение (BINARY), поэтому версия читается из исходника базы
        val src = java.io.File("src/main/kotlin/ru/partitrainer/data/Db.kt").readText()
        assertEquals("1", Regex("""@Database\([^)]*version = (\d+)""").find(src)!!.groupValues[1])
        assertEquals("PartiDatabase", PartiDatabase::class.java.simpleName)
        fun columns(c: Class<*>) = c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.sorted()
        assertEquals(
            listOf("createdAt", "expandRepeats", "fileHash", "id", "isSample", "lineBreaksJson", "marksJson", "pageIndex", "reviewed",
                "rotation", "scoreJson", "selectedVoiceId", "sourcePath", "sourceType", "title", "uncertainJson", "updatedAt"),
            columns(ProjectEntity::class.java),
        )
        assertEquals(listOf("cacheKey", "createdAt", "resultJson"), columns(OmrCacheEntity::class.java))
    }

    @Test fun cachedRecognitionResultStillReads() {
        val cached = """{"score":{"title":"T","keyFifths":0,"timeNum":4,"timeDen":4,"tempo":null,"voices":[{"id":"omr_vocal_top","label":"Голос","kind":"MELODY",
            "notes":[{"midi":60,"startBeat":0.0,"beats":1.0,"measure":0}]}]},"marks":[{"startBeat":0.0,"lengthBeats":4.0}],"uncertain":[0],
            "layout":{"pages":[{"index":0}],"rects":[{"page":0,"x0":0.1,"y0":0.1,"x1":0.5,"y1":0.2}],"notes":{"omr_vocal_top":[{"page":0,"x":0.2,"y":0.15}]}}}"""
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(OmrResultDto.serializer(), cached)
        assertEquals(60, dto.score.voices.single().notes.single().midi)
        assertEquals(listOf(0), dto.uncertain)
        assertEquals(0.2f, dto.layout!!.notes.getValue("omr_vocal_top").single()!!.x, 0f)
        // кэш, записанный до разметки исходника
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(OmrResultDto.serializer(), """{"score":{"title":null,"keyFifths":0,"timeNum":4,"timeDen":4,"tempo":null,"voices":[]},"marks":[],"uncertain":[]}""")
        assertNull(old.layout)
    }
}
