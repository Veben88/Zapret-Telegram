package ru.partitrainer.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Сохранённая партия. Ноты и структура хранятся в JSON (kotlinx.serialization). */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** SAMPLE, PDF, PHOTO, MUSICXML, MIDI, MANUAL */
    val sourceType: String,
    val fileHash: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** JSON: ru.partitrainer.core.model.Score */
    val scoreJson: String,
    /** JSON: List<MeasureMark> */
    val marksJson: String = "[]",
    /** JSON: List<Int> — индексы нот, с которых начинаются строки текста */
    val lineBreaksJson: String = "[]",
    /** JSON: List<Int> — индексы сомнительных тактов (с нуля) */
    val uncertainJson: String = "[]",
    val expandRepeats: Boolean = false,
    val selectedVoiceId: String? = null,
    val reviewed: Boolean = true,
    /** Путь к исходнику во внутреннем хранилище (для миниатюры в редакторе). */
    val sourcePath: String? = null,
    val pageIndex: Int = 0,
    val rotation: Int = 0,
    val isSample: Boolean = false,
)

/** Кэш результата распознавания по хешу файла + движку + выбору страниц/строки. */
@Entity(tableName = "omr_cache")
data class OmrCacheEntity(
    @PrimaryKey val cacheKey: String,
    val resultJson: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
)

@Dao
interface PartiDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY createdAt")
    suspend fun allProjects(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity): Long

    @Query("UPDATE projects SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM omr_cache WHERE cacheKey = :key")
    suspend fun cached(key: String): OmrCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(e: OmrCacheEntity)

    @Query("DELETE FROM omr_cache")
    suspend fun clearCache()

    /** Оставляет только [keep] последних результатов распознавания — кэш не растёт бесконечно. */
    @Query("DELETE FROM omr_cache WHERE cacheKey NOT IN (SELECT cacheKey FROM omr_cache ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun pruneCache(keep: Int)

    @Query("SELECT COUNT(*) FROM projects WHERE sourcePath = :path")
    suspend fun countBySource(path: String): Int

    @Query("SELECT sourcePath FROM projects WHERE id = :id")
    suspend fun sourceOf(id: Long): String?
}

@Database(entities = [ProjectEntity::class, OmrCacheEntity::class], version = 1, exportSchema = true)
abstract class PartiDatabase : RoomDatabase() {
    abstract fun dao(): PartiDao
}
