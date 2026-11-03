package ru.partitrainer.omr

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ru.partitrainer.core.omr.OmrParsed
import ru.partitrainer.data.EngineId
import ru.partitrainer.omr.homr.ChoirAssigner
import ru.partitrainer.omr.homr.ChoirVoice
import ru.partitrainer.omr.homr.EncodedSymbol
import ru.partitrainer.omr.homr.StaffRead
import ru.partitrainer.omr.homr.GrayImage
import ru.partitrainer.omr.homr.HomrPipeline
import ru.partitrainer.omr.homr.StaffDetector
import ru.partitrainer.omr.homr.LayoutBuilder
import ru.partitrainer.omr.homr.SysColumns
import ru.partitrainer.core.omr.PageRef
import ru.partitrainer.omr.homr.TextRecognizer
import ru.partitrainer.omr.homr.Lyrics
import ru.partitrainer.omr.homr.TokenParser

/**
 * Движок «На устройстве»: нейросети homr (segnet + трансформер) через ONNX Runtime, полностью офлайн.
 * Конвейер портирован на Kotlin и проверен против Python-homr (см. docs/OMR_ON_DEVICE.md).
 */
class HomrOnDeviceEngine(
    private val models: HomrModelManager,
    /** Создаёт распознаватель слов песни (если включено «Распознавать слова»); null — без текста. */
    private val textFactory: (() -> TextRecognizer?)? = null,
) : OmrEngine {
    override val id = EngineId.ON_DEVICE

    override suspend fun availability(): Availability =
        if (models.files() != null) Availability.Ready
        else Availability.Unavailable("Модели движка «На устройстве» не скачаны (≈${models.downloadSizeMb} МБ, один раз).")

    /** Прочитанные страницы хора (все нотоносцы) — чтобы четыре голоса не требовали четырёх прогонов. */
    private val choirPages = HashMap<Int, List<List<StaffRead>>>()

    override suspend fun recognize(request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed {
        val files = models.files() ?: throw OmrException("Модели не скачаны", offerManual = false)
        if (request.accurate && !models.segnetInstalled()) {
            throw OmrException("Включён «Точный режим», но модель сегментации (≈${models.segnetSizeMb} МБ) не скачана. Откройте Настройки → Распознавание и включите режим заново.", offerManual = false)
        }
        if (request.staff.isChoirVoice) return recognizeChoir(files, request, onProgress)
        val sel = when (request.staff) {
            StaffSelection.RIGHT_HAND -> StaffDetector.Staff.RIGHT_HAND
            StaffSelection.LEFT_HAND -> StaffDetector.Staff.LEFT_HAND
            else -> StaffDetector.Staff.VOCAL_OR_TOP
        }
        val ctx = currentCoroutineContext()
        val tokens = ArrayList<List<EncodedSymbol>>()
        val lines = ArrayList<List<String>>()
        val cols = ArrayList<SysColumns?>()
        val slots = ArrayList<Int>()
        val text = if (request.lyrics) textFactory?.invoke() else null
        try {
            withContext(Dispatchers.Default) {
                HomrPipeline(files, threads = CpuTopology.performanceThreads(), text = text).use { pipeline ->
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                        val bmp = request.provider.render(page)
                        val gray = try { toGray(bmp) } finally { bmp.recycle() }
                        val r = pipeline.recognizePage(
                            gray, sel, accurate = request.accurate,
                            onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) },
                            isCancelled = { !ctx.isActive },
                        )
                        tokens += r.staffs
                        lines += r.staffs.indices.map { r.lyrics.getOrNull(it).orEmpty() }
                        cols += r.staffs.indices.map { r.columns.getOrNull(it) }
                        slots += r.staffs.map { i }
                        onProgress(OmrProgress(i + 1, request.pages.size, "Страница ${page + 1} готова"))
                    }
                }
            }
        } catch (e: ru.partitrainer.omr.homr.SegnetMissingException) {
            throw OmrException("Нотоносцы не найдены обычным способом. Включите «Точный режим» в настройках (скачает ещё ≈${models.segnetSizeMb} МБ) или введите ноты вручную.", offerManual = true)
        } catch (e: InterruptedException) {
            throw CancellationException("Отменено")
        } catch (e: OutOfMemoryError) {
            throw OmrException("Не хватило памяти. Закройте другие приложения или используйте движок Claude Vision.", offerManual = true)
        } catch (e: ai.onnxruntime.OrtException) {
            throw OmrException("Ошибка нейросети: ${e.message}", offerManual = true, cause = e)
        }
        if (tokens.isEmpty() || tokens.all { it.isEmpty() }) {
            throw OmrException("Нотоносцы на страницах не найдены. Проверьте, что страница ровная и чёткая, или введите ноты вручную.", offerManual = true)
        }
        return TokenParser.parse(tokens, syllables = if (text != null) Lyrics.forStaffs(tokens, lines) else emptyList())
            .copy(layout = LayoutBuilder.build(tokens, cols, slots, request.pages.map { PageRef(it, 0) }))
    }

    private suspend fun recognizeChoir(files: ru.partitrainer.omr.homr.HomrModelFiles, request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed {
        val voice = when (request.staff) {
            StaffSelection.SOPRANO -> ChoirVoice.SOPRANO; StaffSelection.ALTO -> ChoirVoice.ALTO
            StaffSelection.TENOR -> ChoirVoice.TENOR; else -> ChoirVoice.BASS
        }
        val ctx = currentCoroutineContext()
        val tokens = ArrayList<List<EncodedSymbol>>()
        val lines = ArrayList<List<String>>()
        val cols = ArrayList<SysColumns?>()
        val slots = ArrayList<Int>()
        val text = if (request.lyrics) textFactory?.invoke() else null
        var shift = 0
        try {
            withContext(Dispatchers.Default) {
                var pipeline: HomrPipeline? = null
                try {
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        val systems = choirPages[page] ?: run {
                            onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                            val p = pipeline ?: HomrPipeline(files, threads = CpuTopology.performanceThreads(), text = text).also { pipeline = it }
                            val bmp = request.provider.render(page)
                            val gray = try { toGray(bmp) } finally { bmp.recycle() }
                            p.recognizeAll(gray, accurate = request.accurate, onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) }, isCancelled = { !ctx.isActive })
                                .also { choirPages[page] = it }
                        }
                        if (request.twoStaffChoir) {
                            val t = ChoirAssigner.tokensWithLyricsTwoStaff(voice, systems)
                            tokens += t.map { it.tokens }; lines += t.map { it.lyricLines }; cols += t.map { it.columns }; slots += t.map { i }
                        } else {
                            val (t, s) = ChoirAssigner.tokensWithLyrics(voice, systems)
                            tokens += t.map { it.tokens }; lines += t.map { it.lyricLines }; cols += t.map { it.columns }; slots += t.map { i }; if (s != 0) shift = s
                        }
                        onProgress(OmrProgress(i + 1, request.pages.size, "Страница ${page + 1} готова"))
                    }
                } finally { pipeline?.close() }
            }
        } catch (e: ru.partitrainer.omr.homr.SegnetMissingException) {
            throw OmrException("Нотоносцы не найдены обычным способом. Включите «Точный режим» в настройках (скачает ещё ≈${models.segnetSizeMb} МБ) или введите ноты вручную.", offerManual = true)
        } catch (e: InterruptedException) {
            throw CancellationException("Отменено")
        } catch (e: OutOfMemoryError) {
            throw OmrException("Не хватило памяти. Закройте другие приложения или используйте движок Claude Vision.", offerManual = true)
        } catch (e: ai.onnxruntime.OrtException) {
            throw OmrException("Ошибка нейросети: ${e.message}", offerManual = true, cause = e)
        }
        if (tokens.isEmpty() || tokens.all { it.isEmpty() }) {
            throw OmrException("Нотоносцы на страницах не найдены. Проверьте, что страница ровная и чёткая, или введите ноты вручную.", offerManual = true)
        }
        return TokenParser.parse(tokens, shift, if (text != null) Lyrics.forStaffs(tokens, lines) else emptyList())
            .copy(layout = LayoutBuilder.build(tokens, cols, slots, request.pages.map { PageRef(it, 0) }))
    }

    private fun toGray(bmp: Bitmap): GrayImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return GrayImage.fromArgb(bmp.width, bmp.height, px)
    }
}
