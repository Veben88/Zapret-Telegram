package ru.partitrainer.data

import android.content.Context
import kotlinx.serialization.json.Json
import ru.partitrainer.core.omr.SourceLayout
import java.io.File

/** Где такты находятся на страницах исходника — по одному небольшому JSON-файлу на проект (рядом с базой, не в ней). */
class LayoutStore(context: Context) {
    private val dir = File(context.filesDir, "layouts").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: Long) = File(dir, "$id.json")

    fun save(id: Long, layout: SourceLayout) {
        runCatching { file(id).writeText(json.encodeToString(SourceLayout.serializer(), layout)) }
    }

    fun load(id: Long): SourceLayout? = runCatching {
        file(id).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(SourceLayout.serializer(), it) }
    }.getOrNull()

    fun delete(id: Long) { runCatching { file(id).delete() } }
}
