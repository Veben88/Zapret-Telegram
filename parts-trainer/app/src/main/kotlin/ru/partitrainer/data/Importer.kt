package ru.partitrainer.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.partitrainer.core.io.ImportException
import ru.partitrainer.core.io.ImportedPiece
import ru.partitrainer.core.io.MidiParser
import ru.partitrainer.core.io.MusicXmlParser
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.structure.Repeats
import ru.partitrainer.omr.PageRenderer
import ru.partitrainer.util.FileKinds
import ru.partitrainer.util.SourceFiles

sealed interface ImportResult {
    /** Файл разобран напрямую, проект создан. */
    data class Opened(val projectId: Long) : ImportResult
    /** PDF/фото: нужен выбор страниц и распознавание. */
    data class NeedsRecognition(val path: String, val hash: String, val name: String, val isPdf: Boolean) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/** Единая точка импорта: выбор файла, «Открыть с помощью», «Поделиться». */
class Importer(private val sources: SourceFiles, private val projects: ProjectRepository) {

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val imp = sources.import(uri)
            val head = ByteArray(8).also { buf -> imp.file.inputStream().use { it.read(buf) } }
            val title = imp.displayName.substringBeforeLast('.')
            when (FileKinds.detect(imp.displayName, imp.mime, head)) {
                FileKinds.Kind.PDF -> checkPdf(imp.file)?.let { ImportResult.Failed(it) } ?: ImportResult.NeedsRecognition(imp.file.path, imp.hash, title, true)
                FileKinds.Kind.IMAGE -> ImportResult.NeedsRecognition(imp.file.path, imp.hash, title, false)
                FileKinds.Kind.MUSICXML, FileKinds.Kind.MXL -> fromPiece(MusicXmlParser.parseAuto(imp.file.readBytes()), title, "MUSICXML", imp.hash)
                FileKinds.Kind.MIDI -> fromPiece(MidiParser.parse(imp.file.readBytes()), title, "MIDI", imp.hash)
                FileKinds.Kind.UNKNOWN -> ImportResult.Failed("Неизвестный формат файла. Поддерживаются PDF, фото, MusicXML (.musicxml, .xml, .mxl) и MIDI (.mid).")
            }
        } catch (e: ImportException) {
            ImportResult.Failed(e.message ?: "Не удалось прочитать файл")
        } catch (e: Exception) {
            ImportResult.Failed("Не удалось открыть файл: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun fromPiece(piece: ImportedPiece, fileTitle: String, type: String, hash: String): ImportResult {
        val score = piece.score.copy(title = piece.score.title ?: fileTitle)
        val exp = Repeats.expandRepeats(piece.marks)
        val project = Project(
            0, score.title ?: fileTitle, type, score, piece.marks, emptySet(), emptySet(),
            expandRepeats = Repeats.hasStructure(piece.marks) && !exp.ambiguous,
            selectedVoiceId = score.voices.firstOrNull()?.id, reviewed = true, sourcePath = null, pageIndex = 0, rotation = 0, isSample = false,
        )
        return ImportResult.Opened(projects.saveNew(project, hash))
    }

    /** Пробует открыть PDF штатным PdfRenderer; возвращает текст ошибки или null, если всё в порядке. */
    private fun checkPdf(file: java.io.File): String? = try {
        PageRenderer(file, true).use { r -> if (r.pageCount <= 0) "В PDF нет страниц" else null }
    } catch (e: SecurityException) {
        "PDF защищён паролем. Снимите защиту и откройте файл снова."
    } catch (e: Exception) {
        "Не удалось открыть PDF: файл повреждён или имеет неподдерживаемый формат."
    }

    /** Ручной ввод с исходной страницей PDF/фото рядом в редакторе (без распознавания). */
    suspend fun createManualFromSource(path: String, hash: String, title: String, isPdf: Boolean, page: Int, rotation: Int): Long {
        val score = Score(title, 0, 4, 4, null, listOf(Voice("manual", "Голос", VoiceKind.MELODY, emptyList())))
        return projects.saveNew(
            Project(0, title, if (isPdf) "PDF" else "PHOTO", score, emptyList(), emptySet(), emptySet(), false, "manual", true, path, page, rotation, false),
            hash,
        )
    }

    suspend fun createManual(): Long {
        val score = Score("Новая партия", 0, 4, 4, null, listOf(Voice("manual", "Голос", VoiceKind.MELODY, emptyList())))
        return projects.saveNew(
            Project(0, score.title!!, "MANUAL", score, emptyList(), emptySet(), emptySet(), false, "manual", true, null, 0, 0, false),
            null,
        )
    }
}
