package ru.partitrainer.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.partitrainer.core.omr.SourceLayout
import ru.partitrainer.data.LayoutStore
import ru.partitrainer.data.ProjectEntity
import ru.partitrainer.data.ProjectRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
private data class BackupProject(
    val title: String, val sourceType: String, val fileHash: String?, val createdAt: Long, val updatedAt: Long,
    val scoreJson: String, val marksJson: String, val lineBreaksJson: String, val uncertainJson: String,
    val expandRepeats: Boolean, val selectedVoiceId: String?, val reviewed: Boolean,
    /** Имя файла исходника внутри архива (папка sources/); null — исходника нет. */
    val sourceFile: String?, val pageIndex: Int, val rotation: Int, val layout: SourceLayout?,
)

@Serializable
private data class BackupIndex(val format: Int = 1, val projects: List<BackupProject>)

data class RestoreResult(val restored: Int, val skipped: Int)

/**
 * Резервная копия всех партий одним ZIP-файлом: ноты, структура, исходные PDF/фото и раскладка тактов.
 * Файл создаёт и открывает сам пользователь (системный выбор файла) — приложение ничего не отправляет по сети,
 * а автоматическое облачное копирование отключено намеренно (см. PRIVACY.md).
 */
class BackupManager(private val context: Context, private val projects: ProjectRepository, private val layouts: LayoutStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sourcesDir get() = File(context.filesDir, "sources").apply { mkdirs() }

    suspend fun export(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val all = projects.allForBackup()
        val written = HashSet<String>()
        ZipOutputStream(out.buffered()).use { zip ->
            val items = all.map { e ->
                val src = e.sourcePath?.let(::File)?.takeIf { it.isFile }
                if (src != null && written.add(src.name)) {
                    zip.putNextEntry(ZipEntry("sources/${src.name}"))
                    src.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                BackupProject(
                    e.title, e.sourceType, e.fileHash, e.createdAt, e.updatedAt, e.scoreJson, e.marksJson, e.lineBreaksJson, e.uncertainJson,
                    e.expandRepeats, e.selectedVoiceId, e.reviewed, src?.name, e.pageIndex, e.rotation, layouts.load(e.id),
                )
            }
            zip.putNextEntry(ZipEntry("projects.json"))
            zip.write(json.encodeToString(BackupIndex.serializer(), BackupIndex(projects = items)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        all.size
    }

    suspend fun restore(input: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        var index: BackupIndex? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "projects.json" -> index = json.decodeFromString(BackupIndex.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                    name.startsWith("sources/") && !e.isDirectory -> {
                        val safe = File(name).name                       // без путей: защита от ../ в чужом архиве
                        val dest = File(sourcesDir, safe)
                        if (!dest.exists()) dest.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val idx = index ?: throw IllegalArgumentException("Это не резервная копия приложения (нет projects.json)")
        val existing = projects.allForBackup().map { it.title to it.createdAt }.toSet()
        var restored = 0; var skipped = 0
        for (p in idx.projects) {
            if ((p.title to p.createdAt) in existing) { skipped++; continue }
            val src = p.sourceFile?.let { File(sourcesDir, File(it).name) }?.takeIf { it.isFile }
            val id = projects.insertRestored(
                ProjectEntity(
                    title = p.title, sourceType = p.sourceType, fileHash = p.fileHash, createdAt = p.createdAt, updatedAt = p.updatedAt,
                    scoreJson = p.scoreJson, marksJson = p.marksJson, lineBreaksJson = p.lineBreaksJson, uncertainJson = p.uncertainJson,
                    expandRepeats = p.expandRepeats, selectedVoiceId = p.selectedVoiceId, reviewed = p.reviewed,
                    sourcePath = src?.path, pageIndex = p.pageIndex, rotation = p.rotation, isSample = false,
                ),
            )
            p.layout?.let { layouts.save(id, it) }
            restored++
        }
        RestoreResult(restored, skipped)
    }
}
