package ru.partitrainer.omr

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ru.partitrainer.BuildConfig
import ru.partitrainer.omr.homr.MemoryWatch
import ru.partitrainer.omr.homr.OmrProfile
import java.io.File

/**
 * Отчёт о последнем распознавании на устройстве (раздел «Для разработчика»): время этапов по страницам, пиковая память,
 * сведения о телефоне. Пишется только при включённой настройке, хранится в папке приложения и никуда не отправляется —
 * пользователь может сам поделиться файлом.
 */
object OmrReport {
    private fun dir(ctx: Context) = File(ctx.filesDir, "omr_profile").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), "last.json")
    private fun summaryFile(ctx: Context) = File(dir(ctx), "last.txt")

    /** Краткая сводка последнего отчёта (или null). */
    fun summary(ctx: Context): String? = runCatching { summaryFile(ctx).takeIf { it.isFile }?.readText() }.getOrNull()

    fun write(ctx: Context, engine: HomrOnDeviceEngine, totalMs: Long, pages: Int, accurate: Boolean, lyrics: Boolean, units: Int) {
        runCatching {
            val total = OmrProfile("total")
            engine.pageProfiles.toList().forEach(total::merge)
            total.merge(engine.docProfile)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val setup = engine.describeSetup().entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" }
            val json = buildString {
                append("{\"app\":{\"version\":${q(BuildConfig.VERSION_NAME)},\"code\":${BuildConfig.VERSION_CODE}},")
                append("\"device\":{\"manufacturer\":${q(Build.MANUFACTURER)},\"model\":${q(Build.MODEL)},\"sdk\":${Build.VERSION.SDK_INT},")
                append("\"abi\":${q(Build.SUPPORTED_ABIS.firstOrNull() ?: "")},\"cores\":${Runtime.getRuntime().availableProcessors()},")
                append("\"ramMb\":${mi.totalMem / (1 shl 20)},\"javaHeapMaxMb\":${Runtime.getRuntime().maxMemory() / (1 shl 20)}},")
                append("\"setup\":{$setup},")
                append("\"job\":{\"pages\":$pages,\"units\":$units,\"accurate\":$accurate,\"lyrics\":$lyrics},")
                append("\"totalMs\":$totalMs,\"processPeakRssKb\":${MemoryWatch.processPeakRssKb()},")
                append("\"total\":${total.toJson()},")
                append("\"pages\":[${engine.pageProfiles.toList().joinToString(",") { it.toJson() }}],")
                append("\"document\":${engine.docProfile.toJson()}}")
            }
            file(ctx).writeText(json)
            val peak = total.stagePeakRssKb()
            val text = buildString {
                append("${Build.MANUFACTURER} ${Build.MODEL}, ${engine.describeSetup()["encoderBackend"]}\n")
                append("Страниц: $pages, всего ${totalMs} мс, пик памяти ${total.peakRssKb.get() / 1024} МБ (процесс ${MemoryWatch.processPeakRssKb() / 1024} МБ)\n")
                for ((k, v) in total.ms()) append("$k: $v мс${if (k in total.wallStages) " (по часам)" else ""}${peak[k]?.let { ", ${it / 1024} МБ" } ?: ""}\n")
            }
            summaryFile(ctx).writeText(text.trimEnd())
        }
    }

    /** Поделиться JSON-отчётом через системное «Поделиться». */
    fun share(ctx: Context): Boolean {
        val src = file(ctx).takeIf { it.isFile } ?: return false
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "omr_profile.json")
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
