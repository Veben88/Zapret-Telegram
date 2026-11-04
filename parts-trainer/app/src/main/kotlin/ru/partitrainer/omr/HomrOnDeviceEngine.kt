package ru.partitrainer.omr

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
        // Первый запуск: быстро сравниваем int8 на обычных ядрах и с XNNPACK (без загрузок, пара секунд)
        if (models.files() != null && models.encoderBackend == null) {
            onProgress(OmrProgress(0, request.pages.size, "Подбор ускорителя для этого телефона…"))
            runCatching { models.tune(full = false, threads = encoderThreads()) }
        }
        val files = models.files() ?: throw OmrException("Модели не скачаны", offerManual = false)
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
                newPipeline(files, text).use { pipeline ->
                    // Следующая страница рендерится и размечается (поиск линеек), пока нейросеть читает текущую
                    fun prep(page: Int) = async { prepare(pipeline, request, page, ctx) }
                    var next = request.pages.firstOrNull()?.let { prep(it) }
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                        val (gray, loc) = next!!.await()
                        next = request.pages.getOrNull(i + 1)?.let { prep(it) }
                        val r = pipeline.recognizePage(
                            gray, sel, accurate = request.accurate,
                            onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) },
                            isCancelled = { !ctx.isActive }, located = loc,
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
            throw OmrException("Модель сегментации повреждена или удалена. Скачайте модели заново в настройках.", offerManual = true)
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
                    val todo = request.pages.filter { it !in choirPages }
                    fun prep(page: Int) = async {
                        val p = synchronized(this@HomrOnDeviceEngine) { pipeline ?: newPipeline(files, text).also { pipeline = it } }
                        prepare(p, request, page, ctx)
                    }
                    var next = todo.firstOrNull()?.let { it to prep(it) }
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        val systems = choirPages[page] ?: run {
                            onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                            val (gray, loc) = next!!.second.await()
                            next = todo.getOrNull(todo.indexOf(page) + 1)?.let { it to prep(it) }
                            pipeline!!.recognizeAll(gray, accurate = request.accurate, onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) }, isCancelled = { !ctx.isActive }, located = loc)
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
            throw OmrException("Модель сегментации повреждена или удалена. Скачайте модели заново в настройках.", offerManual = true)
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

    /**
     * Строки читаются параллельно: шаг декодера мал, поэтому каждый поток ведёт свою строку в 1 поток ONNX Runtime
     * (≈35–40 % быстрее, чем одна строка за раз во все потоки; результат тот же). Энкодер тяжёл по памяти: на телефонах
     * с большим объёмом ОЗУ он тоже идёт параллельно, иначе — по одному прогону в 2 потока.
     */
    private fun newPipeline(files: ru.partitrainer.omr.homr.HomrModelFiles, text: TextRecognizer?): HomrPipeline {
        val perf = CpuTopology.performanceThreads()
        val backend = models.encoderBackend ?: ru.partitrainer.omr.homr.EncoderBackend.INT8_CPU
        // нейропроцессор (NNAPI) один на всех — прогоны энкодера через него не распараллеливаем
        val serial = !models.bigMemoryDevice || backend.needsNnapi
        return HomrPipeline(
            files, threads = perf, staffWorkers = perf.coerceIn(1, 4), text = text,
            decoderThreads = 1, encoderThreads = encoderThreads(), serialEncoder = serial, encoderBackend = backend,
        )
    }

    /** Потоков на один прогон энкодера: при параллельном чтении строк — 1, иначе 2. */
    private fun encoderThreads(): Int = if (models.bigMemoryDevice) 1 else 2.coerceAtMost(CpuTopology.performanceThreads())

    /** Рендер страницы и поиск нотоносцев (то, что можно делать параллельно с чтением предыдущей страницы). */
    private suspend fun prepare(pipeline: HomrPipeline, request: OmrRequest, page: Int, ctx: kotlin.coroutines.CoroutineContext): Pair<GrayImage, HomrPipeline.Located> {
        val bmp = request.provider.render(page)
        val gray = try { toGray(bmp) } finally { bmp.recycle() }
        return gray to pipeline.locate(gray, request.accurate, { _, _, _ -> }, { !ctx.isActive })
    }

    private fun toGray(bmp: Bitmap): GrayImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return GrayImage.fromArgb(bmp.width, bmp.height, px)
    }
}
