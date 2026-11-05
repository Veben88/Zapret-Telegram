package ru.partitrainer.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ru.partitrainer.core.io.MidiWriter
import ru.partitrainer.core.io.MusicXmlWriter
import ru.partitrainer.core.structure.Repeats
import ru.partitrainer.data.Project
import java.io.File

enum class ExportFormat(val ext: String, val mime: String) {
    MIDI("mid", "audio/midi"), MUSICXML("musicxml", "application/vnd.recordare.musicxml+xml")
}

object Exporter {
    /** Экспорт выбранного голоса (с учётом развёрнутых повторов) через системное «Поделиться». */
    fun share(context: Context, project: Project, voiceId: String?, format: ExportFormat) {
        val score = Repeats.resolve(project.score, project.marks, project.expandRepeats)
        val voice = score.voices.firstOrNull { it.id == voiceId } ?: score.voices.firstOrNull() ?: return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = (project.title.ifBlank { "parts" } + "_" + voice.label).replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        val file = File(dir, "$safe.${format.ext}")
        when (format) {
            ExportFormat.MIDI -> file.writeBytes(MidiWriter.write(score.title, listOf(voice), score.tempo ?: 85, score.timeNum, score.timeDen))
            ExportFormat.MUSICXML -> file.writeText(MusicXmlWriter.write(score, listOf(voice)), Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
