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
        /** Если задан — номер нотоносца в системе (0 — верхний), вместо [selection]. Нужен для хоровых партитур S/A/T/B. */
        staffInSystem: Int? = null,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PageTokens {
        onProgress(0, 1, "Подготовка страницы")
        var page = ImageOps.clahe(ImageOps.resizeToHomrWidth(gray))
        onProgress(0, 1, "Сегментация нот")
        val segnet = Segnet(env, segSession)
        fun segment(img: GrayImage) = segnet.segment(img) { d, t -> onProgress(0, 1, "Сегментация нот ($d/$t)"); if (isCancelled()) throw InterruptedException("Отменено") }
        var seg = segment(page)
        // Фото могут быть наклонены: выравниваем по линейкам и сегментируем заново
        val slope = StaffDetector.estimateSlope(seg)
        if (Math.abs(slope) > 0.005) {
            onProgress(0, 1, "Выравнивание страницы")
            page = ImageOps.rotate(page, Math.atan(slope))
            seg = segment(page)
        }
        val staves = StaffDetector.detect(seg)
        if (staves.isEmpty()) return PageTokens(0, emptyList(), emptyList(), emptyList())
        val systems = StaffDetector.systems(staves, page)
        val chosen = systems.mapNotNull { sys -> (staffInSystem?.takeIf { it < sys.size } ?: if (staffInSystem != null) null else StaffDetector.indexInSystem(selection, sys.size))?.let { sys[it] } }
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

    /**
     * Читает ВСЕ нотоносцы страницы (для хоровых партитур, где голос определяется по ключу и регистру).
     */
    fun recognizeAll(
        gray: GrayImage,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<List<StaffRead>> {
        onProgress(0, 1, "Подготовка страницы")
        var page = ImageOps.clahe(ImageOps.resizeToHomrWidth(gray))
        val segnet = Segnet(env, segSession)
        fun segment(img: GrayImage) = segnet.segment(img) { d, t -> onProgress(0, 1, "Сегментация нот ($d/$t)"); if (isCancelled()) throw InterruptedException("Отменено") }
        var seg = segment(page)
        val slope = StaffDetector.estimateSlope(seg)
        if (Math.abs(slope) > 0.005) { page = ImageOps.rotate(page, Math.atan(slope)); seg = segment(page) }
        val staves = StaffDetector.detect(seg)
        if (staves.isEmpty()) return emptyList()
        val systems = StaffDetector.systems(staves, page)
        val s2s = Staff2Score(env, encSession, decSession, vocab)
        val total = staves.size
        var done = 0
        val out = ArrayList<List<StaffRead>>()
        for ((si, sys) in systems.withIndex()) {
            val reads = ArrayList<StaffRead>()
            for ((i, st) in sys.withIndex()) {
                if (isCancelled()) throw InterruptedException("Отменено")
                onProgress(done, total, "Чтение нотоносца ${done + 1} из $total")
                val tokens = s2s.predict(staffCanvas(page, st, staves), isCancelled)
                val clef = tokens.firstOrNull { it.rhythm.startsWith("clef_") }?.rhythm?.getOrNull(5)
                val notes = tokens.filter { it.rhythm.startsWith("note_") }.mapNotNull { TokenParser.midi(it.pitch, it.lift) }.sorted()
                val oct = StaffDetector.octaveMarkScore(page, st, sys.getOrNull(i + 1)?.minY) > OCTAVE_MARK_THRESHOLD
                reads += StaffRead(st, si, i, tokens, clef, oct && clef != 'F', notes.getOrNull(notes.size / 2))
                done++
            }
            out += reads
        }
        onProgress(total, total, "Страница готова")
        return out
    }

    override fun close() {
        runCatching { segSession.close() }; runCatching { encSession.close() }; runCatching { decSession.close() }
        opts.close()
    }

    companion object {
        /** Порог доли тёмных пикселей под ключом для признака «8» (измерено на хоровой партитуре: тенор 0,04–0,06, остальные ≤ 0,03). */
        const val OCTAVE_MARK_THRESHOLD = 0.035

        /** Изображение строки для трансформера: 256×1280, белый фон, область по правилам homr (prepare_staff_image). */
        fun staffCanvas(page: GrayImage, staff: DetectedStaff, all: List<DetectedStaff>): GrayImage {
            val u = staff.unit
            val xMin = staff.minX - 2 * u; val xMax = staff.maxX + 2 * u
            val xm = (staff.minX + staff.maxX) / 2.0
            val topMid = staff.topAt(xm); val botMid = staff.bottomAt(xm)
            val above = all.filter { it.maxY < staff.minY + 1 && it !== staff && (it.minY + it.maxY) < (staff.minY + staff.maxY) }.maxOfOrNull { it.bottomAt(xm) }
            val below = all.filter { it !== staff && (it.minY + it.maxY) > (staff.minY + staff.maxY) }.minOfOrNull { it.topAt(xm) }
            // область по вертикали — относительно верхней линейки (так строка «выпрямляется» вдоль её изгиба)
            val relMin = max(-4 * u, (above ?: -1e9) - topMid)
            val relMax = min((botMid - topMid) + 4 * u, (below ?: 1e12) - topMid)
            val regionH = relMax - relMin; val regionW = xMax - xMin
            val (cw, ch) = canvasSize(regionH.toInt().coerceAtLeast(1), regionW.toInt().coerceAtLeast(1))
            val canvas = GrayImage(Staff2Score.WIDTH, Staff2Score.HEIGHT).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
            val yOff = (Staff2Score.HEIGHT - ch) / 2
            val top = staff.top
            for (cy in 0 until ch) {
                val ty = cy + yOff
                if (ty < 0 || ty >= Staff2Score.HEIGHT) continue
                val rel = relMin + (cy + 0.5) * regionH / ch
                for (cx in 0 until min(cw, Staff2Score.WIDTH)) {
                    val sx = xMin + (cx + 0.5) * regionW / cw
                    val sy = (top?.at(sx) ?: topMid) + rel
                    canvas.data[ty * Staff2Score.WIDTH + cx] = sampleBilinear(page, sx - 0.5, sy - 0.5).toByte()
                }
            }
            return canvas
        }

        private fun sampleBilinear(img: GrayImage, x: Double, y: Double): Int {
            if (x < 0 || y < 0 || x > img.width - 1 || y > img.height - 1) return 255
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, img.width - 1); val y1 = min(y0 + 1, img.height - 1)
            val ax = x - x0; val ay = y - y0
            val v = (img[x0, y0] * (1 - ax) + img[x1, y0] * ax) * (1 - ay) + (img[x0, y1] * (1 - ax) + img[x1, y1] * ax) * ay
            return Math.round(v).toInt().coerceIn(0, 255)
        }

        /** (ширина, высота) изображения так, чтобы оно вписалось в 1280×256 (get_tr_omr_canvas_size). */
        fun canvasSize(height: Int, width: Int): Pair<Int, Int> {
            val ratio = Staff2Score.HEIGHT.toDouble() / Staff2Score.WIDTH
            return if (height.toDouble() / width > ratio) Pair((width.toDouble() / height * Staff2Score.HEIGHT).toInt().coerceAtLeast(1), Staff2Score.HEIGHT)
            else Pair(Staff2Score.WIDTH, (height.toDouble() / width * Staff2Score.WIDTH).toInt().coerceAtLeast(1))
        }
    }
}
