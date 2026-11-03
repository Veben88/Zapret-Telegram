package ru.partitrainer.omr.homr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Файлы моделей homr (ONNX). */
class HomrModelFiles(val segnet: File, val encoder: File, val decoder: File) {
    fun exist() = segnet.isFile && encoder.isFile && decoder.isFile
}

/** Результат по одной странице. */
class PageTokens(
    /** Сколько нотоносцев найдено на странице всего. */
    val stavesFound: Int,
    /** Токены выбранных нотоносцев в порядке чтения. */
    val staffs: List<List<EncodedSymbol>>,
    /** Диапазоны найденных нотоносцев (для подсветки/отладки). */
    val detected: List<DetectedStaff>,
    val selected: List<DetectedStaff>,
)

/**
 * Конвейер homr на Kotlin: подготовка страницы → segnet → нотоносцы → трансформер.
 * Упрощения относительно оригинала: нет автообрезки фото и выпрямления линеек (страницы PDF прямые).
 */
class HomrPipeline(private val files: HomrModelFiles, threads: Int = 0) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { if (threads > 0) setIntraOpNumThreads(threads) }
    private val segSession by lazy { env.createSession(files.segnet.path, opts) }
    private val encSession by lazy { env.createSession(files.encoder.path, opts) }
    private val decSession by lazy { env.createSession(files.decoder.path, opts) }
    private val vocab = HomrVocab.fromResources()

    /** Шаги: сегментация (0..1), затем по одному на нотоносец. */
    fun recognizePage(
        gray: GrayImage, selection: StaffDetector.Staff,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PageTokens {
        onProgress(0, 1, "Подготовка страницы")
        val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(gray))
        onProgress(0, 1, "Сегментация нот")
        val seg = Segnet(env, segSession).segment(page) { d, t -> onProgress(0, 1, "Сегментация нот ($d/$t)"); if (isCancelled()) throw InterruptedException("Отменено") }
        val staves = StaffDetector.detect(seg)
        if (staves.isEmpty()) return PageTokens(0, emptyList(), emptyList(), emptyList())
        val systems = StaffDetector.systems(staves, page)
        val chosen = systems.mapNotNull { sys -> StaffDetector.indexInSystem(selection, sys.size)?.let { sys[it] } }
        val s2s = Staff2Score(env, encSession, decSession, vocab)
        val result = ArrayList<List<EncodedSymbol>>()
        for ((i, st) in chosen.withIndex()) {
            if (isCancelled()) throw InterruptedException("Отменено")
            onProgress(i, chosen.size, "Чтение нотоносца ${i + 1} из ${chosen.size}")
            result += s2s.predict(staffCanvas(page, st, staves), isCancelled)
        }
        onProgress(chosen.size, chosen.size, "Страница готова")
        return PageTokens(staves.size, result, staves, chosen)
    }

    override fun close() {
        runCatching { segSession.close() }; runCatching { encSession.close() }; runCatching { decSession.close() }
        opts.close()
    }

    companion object {
        /** Изображение строки для трансформера: 256×1280, белый фон, область по правилам homr (prepare_staff_image). */
        fun staffCanvas(page: GrayImage, staff: DetectedStaff, all: List<DetectedStaff>): GrayImage {
            val u = staff.unit
            val xMin = staff.minX - 2 * u; val xMax = staff.maxX + 2 * u
            val above = all.filter { it.minY < staff.minY }.maxOfOrNull { it.maxY.toDouble() } ?: 0.0
            val below = all.filter { it.maxY > staff.maxY }.minOfOrNull { it.minY.toDouble() } ?: 1e12
            val yMin = max(staff.minY - 4 * u, above); val yMax = min(staff.maxY + 4 * u, below)
            val x0 = xMin.toInt(); val y0 = yMin.toInt(); val x1 = xMax.toInt(); val y1 = yMax.toInt()
            val (cw, ch) = canvasSize(y1 - y0, x1 - x0)
            val crop = page.crop(x0, y0, x1, y1)
            val resized = ImageOps.resizeBilinear(crop, cw, ch)
            val canvas = GrayImage(Staff2Score.WIDTH, Staff2Score.HEIGHT).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
            val yOff = (Staff2Score.HEIGHT - ch) / 2
            for (y in 0 until ch) {
                val ty = y + yOff
                if (ty < 0 || ty >= Staff2Score.HEIGHT) continue
                System.arraycopy(resized.data, y * cw, canvas.data, ty * Staff2Score.WIDTH, min(cw, Staff2Score.WIDTH))
            }
            return canvas
        }

        /** (ширина, высота) изображения так, чтобы оно вписалось в 1280×256 (get_tr_omr_canvas_size). */
        fun canvasSize(height: Int, width: Int): Pair<Int, Int> {
            val ratio = Staff2Score.HEIGHT.toDouble() / Staff2Score.WIDTH
            return if (height.toDouble() / width > ratio) Pair((width.toDouble() / height * Staff2Score.HEIGHT).toInt().coerceAtLeast(1), Staff2Score.HEIGHT)
            else Pair(Staff2Score.WIDTH, (height.toDouble() / width * Staff2Score.WIDTH).toInt().coerceAtLeast(1))
        }
    }
}
