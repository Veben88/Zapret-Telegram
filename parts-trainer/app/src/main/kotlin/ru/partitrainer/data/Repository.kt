package ru.partitrainer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.music.BuiltinSong
import ru.partitrainer.core.structure.MeasureMark

/** Загруженный проект в виде, удобном для UI. */
data class Project(
    val id: Long,
    val title: String,
    val sourceType: String,
    val score: Score,
    val marks: List<MeasureMark>,
    val lineBreaks: Set<Int>,
    val uncertain: Set<Int>,
    val expandRepeats: Boolean,
    val selectedVoiceId: String?,
    val reviewed: Boolean,
    val sourcePath: String?,
    val pageIndex: Int,
    val rotation: Int,
    val isSample: Boolean,
)

class ProjectRepository(private val dao: PartiDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val marksSer = ListSerializer(MeasureMark.serializer())
    private val intsSer = ListSerializer(Int.serializer())

    fun observe(): Flow<List<ProjectEntity>> = dao.observeProjects()

    suspend fun load(id: Long): Project? = dao.project(id)?.toProject()

    suspend fun save(p: Project, now: Long = System.currentTimeMillis()): Long {
        val old = if (p.id != 0L) dao.project(p.id) else null
        return dao.upsert(
            ProjectEntity(
                id = p.id, title = p.title, sourceType = p.sourceType, fileHash = old?.fileHash,
                createdAt = old?.createdAt ?: now, updatedAt = now,
                scoreJson = json.encodeToString(Score.serializer(), p.score),
                marksJson = json.encodeToString(marksSer, p.marks),
                lineBreaksJson = json.encodeToString(intsSer, p.lineBreaks.sorted()),
                uncertainJson = json.encodeToString(intsSer, p.uncertain.sorted()),
                expandRepeats = p.expandRepeats, selectedVoiceId = p.selectedVoiceId, reviewed = p.reviewed,
                sourcePath = p.sourcePath, pageIndex = p.pageIndex, rotation = p.rotation, isSample = p.isSample,
            ).copy(fileHash = old?.fileHash),
        )
    }

    suspend fun saveNew(p: Project, fileHash: String?): Long {
        val id = save(p.copy(id = 0))
        if (fileHash != null) dao.project(id)?.let { dao.upsert(it.copy(fileHash = fileHash)) }
        return id
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title.trim().ifEmpty { "Без названия" }, System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun cached(key: String): String? = dao.cached(key)?.resultJson
    suspend fun putCache(key: String, resultJson: String) = dao.putCache(OmrCacheEntity(key, resultJson, System.currentTimeMillis()))
    suspend fun clearCache() = dao.clearCache()

    /** Создаёт проект из встроенного примера (или возвращает существующий). */
    suspend fun ensureSample(): Long {
        val built = BuiltinSong.build()
        val now = System.currentTimeMillis()
        val p = Project(
            0, BuiltinSong.TITLE, "SAMPLE", built.score, emptyList(), built.lineStarts, emptySet(), false,
            built.score.voices.first().id, true, null, 0, 0, true,
        )
        return save(p, now)
    }

    private fun ProjectEntity.toProject() = Project(
        id = id, title = title, sourceType = sourceType,
        score = json.decodeFromString(Score.serializer(), scoreJson),
        marks = json.decodeFromString(marksSer, marksJson),
        lineBreaks = json.decodeFromString(intsSer, lineBreaksJson).toSet(),
        uncertain = json.decodeFromString(intsSer, uncertainJson).toSet(),
        expandRepeats = expandRepeats, selectedVoiceId = selectedVoiceId, reviewed = reviewed,
        sourcePath = sourcePath, pageIndex = pageIndex, rotation = rotation, isSample = isSample,
    )
}
