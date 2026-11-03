package ru.partitrainer.omr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.partitrainer.R
import ru.partitrainer.container
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.omr.ClaudeOmr
import ru.partitrainer.core.structure.MeasureMark
import ru.partitrainer.data.EngineId
import ru.partitrainer.data.Project
import java.io.File

@Serializable
data class OmrResultDto(val score: Score, val marks: List<MeasureMark>, val uncertain: List<Int>)

/** Параметры задания; передаются через Data WorkManager (без больших объектов). */
data class OmrJob(
    val filePath: String, val hash: String, val isPdf: Boolean, val title: String,
    val pages: List<Int>, val rotations: List<Int>, val staff: StaffSelection, val engine: EngineId,
) {
    fun cacheKey() = "$hash|${engine.name}|${pages.zip(rotations).joinToString(",") { "${it.first}r${it.second}" }}|${staff.key}"

    fun toData(): Data = workDataOf(
        "path" to filePath, "hash" to hash, "pdf" to isPdf, "title" to title,
        "pages" to pages.toIntArray(), "rot" to rotations.toIntArray(), "staff" to staff.key, "engine" to engine.name,
    )

    companion object {
        fun from(d: Data) = OmrJob(
            d.getString("path")!!, d.getString("hash")!!, d.getBoolean("pdf", true), d.getString("title") ?: "Без названия",
            d.getIntArray("pages")!!.toList(), d.getIntArray("rot")!!.toList(), StaffSelection.fromKey(d.getString("staff")),
            EngineId.valueOf(d.getString("engine")!!),
        )
        const val TAG = "omr"
        const val KEY_PROJECT = "projectId"
        const val KEY_ERROR = "error"
        const val KEY_MANUAL = "manual"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MSG = "msg"

        fun enqueue(context: Context, job: OmrJob): java.util.UUID {
            val req = OneTimeWorkRequestBuilder<OmrWorker>().setInputData(job.toData()).addTag(TAG).build()
            WorkManager.getInstance(context).enqueue(req)
            return req.id
        }
    }
}

/** Распознавание в фоне: foreground-уведомление, прогресс по страницам, отмена через WorkManager. */
class OmrWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Подготовка…", 0, 0)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(0, applicationContext.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result {
        val job = OmrJob.from(inputData)
        val c = applicationContext.container
        try {
            setForeground(foreground("Запуск…", 0, 0))
        } catch (_: Exception) { /* уведомления могут быть запрещены — продолжаем без них */ }

        // Кэш по хешу файла
        c.projects.cached(job.cacheKey())?.let { cached ->
            val dto = json.decodeFromString(OmrResultDto.serializer(), cached)
            return Result.success(workDataOf(OmrJob.KEY_PROJECT to saveProject(job, dto)))
        }

        val engine: OmrEngine = when (job.engine) {
            EngineId.ON_DEVICE -> HomrOnDeviceEngine(c.homrModels)
            EngineId.CLAUDE_VISION -> ClaudeVisionEngine { c.keyStore.load() }
        }
        (engine.availability() as? Availability.Unavailable)?.let {
            return Result.failure(workDataOf(OmrJob.KEY_ERROR to it.reason, OmrJob.KEY_MANUAL to true))
        }

        PageRenderer(File(job.filePath), job.isPdf).use { renderer ->
            val rotByPage = job.pages.zip(job.rotations).toMap()
            val staffs = job.staff.expand()
            val totalUnits = job.pages.size * staffs.size
            val voices = ArrayList<ru.partitrainer.core.model.Voice>()
            var base: ClaudeOmr.Assembled? = null
            try {
                for ((si, staff) in staffs.withIndex()) {
                    val parsed = engine.recognize(OmrRequest(job.pages, staff) { p -> renderer.render(p, rotByPage[p] ?: 0) }) { pr ->
                        val done = si * job.pages.size + pr.done
                        // Сообщение о ходе работы и счётчики (setProgress в корутине — через launch не нужен: вызываем из колбэка синхронно)
                        publish(done, totalUnits, pr.message)
                    }
                    val asm = ClaudeOmr.assemble(parsed, staff.voiceLabel)
                    if (base == null) base = asm
                    voices += asm.score.voices.single().copy(id = "omr_${staff.key}")
                }
            } catch (e: OmrException) {
                return Result.failure(workDataOf(OmrJob.KEY_ERROR to e.userMessage, OmrJob.KEY_MANUAL to e.offerManual))
            }
            val b = base!!
            val score = b.score.copy(title = b.score.title ?: job.title, voices = voices)
            val dto = OmrResultDto(score, b.marks, b.uncertainMeasures)
            c.projects.putCache(job.cacheKey(), json.encodeToString(OmrResultDto.serializer(), dto))
            return Result.success(workDataOf(OmrJob.KEY_PROJECT to saveProject(job, dto)))
        }
    }

    @Volatile private var lastPublish = 0L
    private fun publish(done: Int, total: Int, msg: String) {
        // Колбэк не suspend — используем неблокирующую отправку через setProgressAsync.
        setProgressAsync(workDataOf(OmrJob.KEY_DONE to done, OmrJob.KEY_TOTAL to total, OmrJob.KEY_MSG to msg))
        val now = System.currentTimeMillis()
        if (now - lastPublish > 800) {
            lastPublish = now
            runCatching {
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, foreground(msg, done, total).notification)
            }
        }
    }

    private suspend fun saveProject(job: OmrJob, dto: OmrResultDto): Long {
        val c = applicationContext.container
        val p = Project(
            0, dto.score.title ?: job.title, if (job.isPdf) "PDF" else "PHOTO", dto.score, dto.marks, emptySet(),
            dto.uncertain.toSet(), false, dto.score.voices.firstOrNull()?.id, false, job.filePath,
            job.pages.firstOrNull() ?: 0, job.rotations.firstOrNull() ?: 0, false,
        )
        return c.projects.saveNew(p, job.hash)
    }

    private companion object {
        const val CHANNEL = "omr"
        const val NOTIF_ID = 4711
    }
}
