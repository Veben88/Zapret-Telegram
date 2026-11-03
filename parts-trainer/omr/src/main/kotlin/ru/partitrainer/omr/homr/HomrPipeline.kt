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
    /** Строки текста под каждым выбранным нотоносцем (пусто, если распознавание текста выключено). */
    val lyrics: List<List<String>> = emptyList(),
)

/**
 * Конвейер homr на Kotlin: подготовка страницы → segnet → нотоносцы → трансформер.
 * Упрощения относительно оригинала: нет автообрезки фото и выпрямления линеек (страницы PDF прямые).
 */
class HomrPipeline(
    private val files: HomrModelFiles, threads: Int = 0, private val staffWorkers: Int = defaultWorkers(),
    /** Если задан — под каждым прочитанным нотоносцем распознаётся текст (слова песни). */
    private val text: TextRecognizer? = null,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { if (threads > 0) setIntraOpNumThreads(threads) }
    private val segSession by lazy { env.createSession(files.segnet.path, opts) }
    private val encSession by lazy { env.createSession(files.encoder.path, opts) }
    private val decSession by lazy { env.createSession(files.decoder.path, opts) }
    private val vocab = HomrVocab.fromResources()

    /** Время этапов последней страницы, мс (для профилирования и тестов). */
    val timings = LinkedHashMap<String, Long>()
    @Volatile var lastS2s: Staff2Score? = null
    /** Использовался ли быстрый поиск линеек без нейросети (последняя страница). */
    @Volatile var usedClassic = false
    private inline fun <T> timed(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { timings[name] = (timings[name] ?: 0L) + (System.nanoTime() - t0) / 1_000_000 }
    }

    /** Результат поиска нотоносцев: выровненная страница, нотоносцы и системы. */
    class Located(val page: GrayImage, val staves: List<DetectedStaff>, val systems: List<List<DetectedStaff>>)

    /**
     * Находит нотоносцы. Быстрый путь — без нейросети (выравнивание по тёмным пикселям + поиск длинных тонких линий);
     * если так ничего не найдено или включён [accurate], используется нейросеть сегментации.
     */
    fun locate(
        gray: GrayImage, accurate: Boolean,
        onProgress: (done: Int, total: Int, message: String) -> Unit, isCancelled: () -> Boolean,
    ): Located {
        var page = timed("prep") { ImageOps.clahe(ImageOps.resizeToHomrWidth(gray)) }
        if (!accurate) {
            val located = timed("classic") {
                val dark = ClassicStaffMask.dark(page)
                val slope = ClassicStaffMask.estimateSlope(dark, page.width, page.height)
                val p = if (Math.abs(slope) > 0.005) ImageOps.rotate(page, Math.atan(slope)) else page
                val d = if (p === page) dark else ClassicStaffMask.dark(p)
                val staves = StaffDetector.detect(ClassicStaffMask.mask(p, d))
                if (staves.isEmpty()) null else Located(p, staves, StaffDetector.systems(staves, p))
            }
            if (located != null) { usedClassic = true; return located }
        }
        usedClassic = false
        onProgress(0, 1, "Сегментация нот")
        val segnet = Segnet(env, segSession)
        fun segment(img: GrayImage) = timed("segnet") { segnet.segment(img) { d, t -> onProgress(0, 1, "Сегментация нот ($d/$t)"); if (isCancelled()) throw InterruptedException("Отменено") } }
        var seg = segment(page)
        val slope = timed("deskew") { StaffDetector.estimateSlope(seg) }
        if (Math.abs(slope) > 0.005) timed("deskew") { page = ImageOps.rotate(page, Math.atan(slope)); seg = seg.rotated(Math.atan(slope)) }
        val staves = timed("detect") { StaffDetector.detect(seg) }
        return Located(page, staves, if (staves.isEmpty()) emptyList() else StaffDetector.systems(staves, page))
    }

    /** Шаги: сегментация (0..1), затем по одному на нотоносец. */
    fun recognizePage(
        gray: GrayImage, selection: StaffDetector.Staff,
        /** Если задан — номер нотоносца в системе (0 — верхний), вместо [selection]. Нужен для хоровых партитур S/A/T/B. */
        staffInSystem: Int? = null,
        accurate: Boolean = false,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PageTokens {
        timings.clear()
        onProgress(0, 1, "Подготовка страницы")
        val loc = locate(gray, accurate, onProgress, isCancelled)
        val page = loc.page; val staves = loc.staves; val systems = loc.systems
        if (staves.isEmpty()) return PageTokens(0, emptyList(), emptyList(), emptyList())
        val chosen = systems.mapNotNull { sys -> (staffInSystem?.takeIf { it < sys.size } ?: if (staffInSystem != null) null else StaffDetector.indexInSystem(selection, sys.size))?.let { sys[it] } }
        val s2s = Staff2Score(env, encSession, decSession, vocab)
        val canvases = chosen.map { st -> staffCanvas(page, st, staves) }
        val result = predictAll(s2s, canvases, isCancelled) { done -> onProgress(done, chosen.size, "Чтение нотоносца $done из ${chosen.size}") }
        val lyrics = text?.let { rec ->
            onProgress(chosen.size, chosen.size, "Распознавание текста")
            chosen.map { st -> if (isCancelled()) throw InterruptedException("Отменено"); Lyrics.readLines(rec, page, st, nextStaffOf(st, staves)) }
        } ?: emptyList()
        onProgress(chosen.size, chosen.size, "Страница готова")
        return PageTokens(staves.size, result, staves, chosen, lyrics)
    }

    /**
     * Читает ВСЕ нотоносцы страницы (для хоровых партитур, где голос определяется по ключу и регистру).
     */
    fun recognizeAll(
        gray: GrayImage,
        accurate: Boolean = false,
        onProgress: (done: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<List<StaffRead>> {
        timings.clear()
        onProgress(0, 1, "Подготовка страницы")
        val loc = locate(gray, accurate, onProgress, isCancelled)
        val page = loc.page; val staves = loc.staves; val systems = loc.systems
        if (staves.isEmpty()) return emptyList()
        val s2s = Staff2Score(env, encSession, decSession, vocab)
        lastS2s = s2s
        val total = staves.size
        val jobs = ArrayList<Triple<Int, Int, DetectedStaff>>()           // система, номер в системе, нотоносец
        for ((si, sys) in systems.withIndex()) for ((i, st) in sys.withIndex()) jobs += Triple(si, i, st)
        val canvases = jobs.map { (_, _, st) -> timed("canvas") { staffCanvas(page, st, staves) } }
        val allTokens = timed("transformer") {
            predictAll(s2s, canvases, isCancelled) { done -> onProgress(done, total, "Чтение нотоносца $done из $total") }
        }
        val out = ArrayList<List<StaffRead>>()
        for ((si, sys) in systems.withIndex()) {
            val reads = ArrayList<StaffRead>()
            for ((i, st) in sys.withIndex()) {
                val tokens = allTokens[jobs.indexOfFirst { it.third === st }]
                val clef = tokens.firstOrNull { it.rhythm.startsWith("clef_") }?.rhythm?.getOrNull(5)
                val notes = tokens.filter { it.rhythm.startsWith("note_") }.mapNotNull { TokenParser.midi(it.pitch, it.lift) }.sorted()
                // начало строк в системе выровнено — берём медиану (в сканах край отдельной строки «плавает»)
                val leftEdge = sys.map { it.minX }.sorted().let { it[it.size / 2] }.toDouble()
                val octScore = StaffDetector.octaveMarkScore(page, st, sys.getOrNull(i + 1)?.minY, leftEdge)
                val oct = octScore > OCTAVE_MARK_THRESHOLD
                val lines = text?.let { rec -> Lyrics.readLines(rec, page, st, nextStaffOf(st, staves)) } ?: emptyList()
                reads += StaffRead(st, si, i, tokens, clef, oct && clef != 'F', notes.getOrNull(notes.size / 2), octScore, lines)
            }
            out += reads
        }
        onProgress(total, total, "Страница готова")
        return out
    }

    /** Следующий по высоте нотоносец страницы (ограничивает область текста снизу). */
    private fun nextStaffOf(st: DetectedStaff, all: List<DetectedStaff>): DetectedStaff? =
        all.filter { it.minY > st.minY && it !== st }.minByOrNull { it.minY }

    /**
     * Читает нотоносцы (энкодер+декодер) в нескольких потоках: шаг декодера — много мелких операций, одному потоку
     * ядра не загрузить. Сессии ONNX Runtime потокобезопасны. Порядок результатов сохраняется.
     */
    private fun predictAll(
        s2s: Staff2Score, canvases: List<GrayImage>, isCancelled: () -> Boolean, onDone: (Int) -> Unit,
    ): List<List<EncodedSymbol>> {
        if (canvases.isEmpty()) return emptyList()
        val workers = staffWorkers.coerceIn(1, canvases.size)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        if (workers == 1) return canvases.map { c ->
            if (isCancelled()) throw InterruptedException("Отменено")
            s2s.predict(c, isCancelled).also { onDone(done.incrementAndGet()) }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r -> Thread(r, "homr-staff").also { it.isDaemon = true } }
        try {
            val futures = canvases.map { c -> pool.submit<List<EncodedSymbol>> {
                if (isCancelled()) throw InterruptedException("Отменено")
                s2s.predict(c, isCancelled).also { onDone(done.incrementAndGet()) }
            } }
            return futures.map {
                try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            }
        } finally { pool.shutdownNow() }
    }

    override fun close() {
        runCatching { segSession.close() }; runCatching { encSession.close() }; runCatching { decSession.close() }
        opts.close()
    }

    companion object {
        /** Порог доли тёмных пикселей под ключом для признака «8»: по 82 тенорам и 164 другим строкам тенор ≥ 0,035 (медиана 0,086), остальные ≤ 0,049. */
        const val OCTAVE_MARK_THRESHOLD = 0.05

        /**
         * Число потоков чтения нотоносцев. По замерам параллельное чтение не ускоряет: энкодер уже занимает все ядра,
         * а второй поток только конкурирует за них и память. Поэтому по умолчанию один.
         */
        fun defaultWorkers(): Int = 1

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
