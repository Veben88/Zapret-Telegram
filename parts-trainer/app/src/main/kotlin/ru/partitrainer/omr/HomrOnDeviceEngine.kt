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
import ru.partitrainer.omr.homr.TokenParser

/**
 * Движок «На устройстве»: нейросети homr (segnet + трансформер) через ONNX Runtime, полностью офлайн.
 * Конвейер портирован на Kotlin и проверен против Python-homr (см. docs/OMR_ON_DEVICE.md).
 */
class HomrOnDeviceEngine(private val models: HomrModelManager) : OmrEngine {
    override val id = EngineId.ON_DEVICE

    override suspend fun availability(): Availability =
        if (models.files() != null) Availability.Ready
        else Availability.Unavailable("Модели движка «На устройстве» не скачаны (≈${models.downloadSizeMb} МБ, один раз).")

    /** Прочитанные страницы хора (все нотоносцы) — чтобы четыре голоса не требовали четырёх прогонов. */
    private val choirPages = HashMap<Int, List<List<StaffRead>>>()

    override suspend fun recognize(request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed {
        val files = models.files() ?: throw OmrException("Модели не скачаны", offerManual = false)
        if (request.staff.isChoirVoice) return recognizeChoir(files, request, onProgress)
        val sel = when (request.staff) {
            StaffSelection.RIGHT_HAND -> StaffDetector.Staff.RIGHT_HAND
            StaffSelection.LEFT_HAND -> StaffDetector.Staff.LEFT_HAND
            else -> StaffDetector.Staff.VOCAL_OR_TOP
        }
        val ctx = currentCoroutineContext()
        val tokens = ArrayList<List<EncodedSymbol>>()
        try {
            withContext(Dispatchers.Default) {
                HomrPipeline(files).use { pipeline ->
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                        val bmp = request.provider.render(page)
                        val gray = try { toGray(bmp) } finally { bmp.recycle() }
                        val r = pipeline.recognizePage(
                            gray, sel,
                            onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) },
                            isCancelled = { !ctx.isActive },
                        )
                        tokens += r.staffs
                        onProgress(OmrProgress(i + 1, request.pages.size, "Страница ${page + 1} готова"))
                    }
                }
            }
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
        return TokenParser.parse(tokens)
    }

    private suspend fun recognizeChoir(files: ru.partitrainer.omr.homr.HomrModelFiles, request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed {
        val voice = when (request.staff) {
            StaffSelection.SOPRANO -> ChoirVoice.SOPRANO; StaffSelection.ALTO -> ChoirVoice.ALTO
            StaffSelection.TENOR -> ChoirVoice.TENOR; else -> ChoirVoice.BASS
        }
        val ctx = currentCoroutineContext()
        val tokens = ArrayList<List<EncodedSymbol>>()
        var shift = 0
        try {
            withContext(Dispatchers.Default) {
                var pipeline: HomrPipeline? = null
                try {
                    for ((i, page) in request.pages.withIndex()) {
                        ctx.ensureActive()
                        val systems = choirPages[page] ?: run {
                            onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: подготовка"))
                            val p = pipeline ?: HomrPipeline(files).also { pipeline = it }
                            val bmp = request.provider.render(page)
                            val gray = try { toGray(bmp) } finally { bmp.recycle() }
                            p.recognizeAll(gray, onProgress = { _, _, msg -> onProgress(OmrProgress(i, request.pages.size, "Страница ${page + 1}: $msg")) }, isCancelled = { !ctx.isActive })
                                .also { choirPages[page] = it }
                        }
                        if (request.twoStaffChoir) {
                            tokens += ChoirAssigner.tokensForTwoStaff(voice, systems)
                        } else {
                            val (t, s) = ChoirAssigner.tokensFor(voice, systems)
                            tokens += t; if (s != 0) shift = s
                        }
                        onProgress(OmrProgress(i + 1, request.pages.size, "Страница ${page + 1} готова"))
                    }
                } finally { pipeline?.close() }
            }
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
        return TokenParser.parse(tokens, shift)
    }

    private fun toGray(bmp: Bitmap): GrayImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return GrayImage.fromArgb(bmp.width, bmp.height, px)
    }
}
